package io.github.nhwalker.helm.gradle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactSpec;
import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies;
import io.github.nhwalker.artifacts.gradle.support.LifecycleSupport;
import io.github.nhwalker.artifacts.gradle.support.PublishingSupport;
import io.github.nhwalker.artifacts.gradle.support.ResourceImports;
import io.github.nhwalker.helm.gradle.dependency.HelmAttributes;
import io.github.nhwalker.helm.gradle.dsl.HelmChart;
import io.github.nhwalker.helm.gradle.tasks.AbstractHelmTask;
import io.github.nhwalker.helm.gradle.tasks.HelmLintTask;
import io.github.nhwalker.helm.gradle.tasks.HelmPackageTask;
import io.github.nhwalker.helm.gradle.tasks.HelmStageTask;

/**
 * Registers the {@code helm} extension, applies its configuration as conventions
 * to every helm task, and turns each declared chart into stage/package/lint tasks
 * plus the consumable configuration and software-component variant used to share
 * packaged charts as dependencies between projects.
 *
 * <p>Charts opt into being bundled into the project's jar resources with
 * {@link HelmChart#importResourcesTask()} (mirroring the generic artifacts DSL); when a chart is
 * bundled and the {@code java} plugin is applied, the plugin also generates a
 * {@code <ProjectName>Charts} Java interface (per source set) exposing the bundled charts' resource
 * paths.
 *
 * <p>Apply with:
 * <pre>
 * plugins { id 'io.github.nhwalker.helm' }
 * </pre>
 */
public class HelmPlugin implements Plugin<Project> {

    /** The name of the project extension contributed by this plugin. */
    public static final String EXTENSION_NAME = "helm";

    /** The task group applied to every helm task. */
    public static final String TASK_GROUP = "helm";

    /** The task that generates the {@code main} source set's {@code <ProjectName>Charts} interface. */
    public static final String GENERATE_REFERENCES_TASK = "generateChartReferences";

    /** The {@code <Domain>} segment of this plugin's generated interface name. */
    public static final String REFERENCES_DOMAIN = "Charts";

    /** The {@code <Noun>} segment of this plugin's references task names. */
    private static final String REFERENCES_TASK_NOUN = "Chart";

    /**
     * The references task name for a source set: {@link #GENERATE_REFERENCES_TASK} for {@code main},
     * {@code generate<SourceSet>ChartReferences} otherwise.
     */
    public static String generateReferencesTaskName(String sourceSetName) {
        return ResourceImports.generateReferencesTaskName(sourceSetName, REFERENCES_TASK_NOUN);
    }

    private final SoftwareComponentFactory softwareComponentFactory;

    @Inject
    public HelmPlugin(SoftwareComponentFactory softwareComponentFactory) {
        this.softwareComponentFactory = softwareComponentFactory;
    }

    @Override
    public void apply(Project project) {
        HelmExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, HelmExtension.class);
        extension.getExecutable().convention("helm");
        ResourceImports.applyExtensionConventions(project, extension, REFERENCES_DOMAIN);

        // Contribute assemble/check/build/clean so charts can be wired into the standard build,
        // even in a project that does not also apply the java/base plugin (idempotent if it does).
        project.getPluginManager().apply(org.gradle.language.base.plugins.LifecycleBasePlugin.class);

        project.getTasks().withType(AbstractHelmTask.class).configureEach(task -> {
            task.setGroup(TASK_GROUP);
            task.getExecutable().convention(extension.getExecutable());
            task.getGlobalOptions().convention(extension.getGlobalOptions());
        });

        // Charts are modeled as generic artifacts: register the core artifact schema
        // and the helm free-attribute keys.
        ArtifactsDependencies.registerSchema(project);
        ArtifactsDependencies.registerAttributeKey(project, HelmAttributes.CHART_NAME_KEY);
        ArtifactsDependencies.registerAttributeKey(project, HelmAttributes.CHART_TYPE_KEY);

        // One shared component aggregates every chart's variants (one module/coordinate), the same way
        // the java component carries the main + sources/javadoc jars. Created eagerly so
        // `components.genericArtifacts` is resolvable in the publishing block.
        PublishingSupport.registerComponent(project, softwareComponentFactory);

        // Materialize each chart's tasks/configs once the DSL is fully evaluated, so
        // structural decisions (e.g. whether a lint task exists) see final values.
        project.afterEvaluate(p -> {
            extension.getCharts().forEach(chart -> registerChart(p, extension, chart));
            // When a Java plugin is applied, expose the resource paths of the charts that bundled
            // themselves into resources through a generated interface. No-ops when none bundled.
            if (p.getPluginManager().hasPlugin("java")) {
                registerReferences(p, extension);
            }
        });
    }

    private void registerReferences(Project project, HelmExtension extension) {
        // Group each bundled chart's constant under the source set it was bundled into, so each
        // source set gets its own <ProjectName>References[<SourceSet>] interface.
        Map<String, Map<String, Provider<String>>> constantsBySourceSet = new LinkedHashMap<>();
        Map<String, List<TaskProvider<? extends Task>>> bundlesBySourceSet = new LinkedHashMap<>();
        extension.getCharts().forEach(chart -> chart.getResourceBundles().forEach((sourceSet, bundle) -> {
            // Deterministic: a bundled chart always lands at charts/<chart>.tgz.
            constantsBySourceSet.computeIfAbsent(sourceSet, k -> new LinkedHashMap<>())
                    .put(chart.getName(), project.provider(() -> HelmChart.jarResourcePath(chart.getName())));
            bundlesBySourceSet.computeIfAbsent(sourceSet, k -> new ArrayList<>()).add(bundle);
        }));
        ResourceImports.generateReferencesForSourceSets(project, TASK_GROUP, REFERENCES_TASK_NOUN,
                "helmChartRefs", "Generated by the io.github.nhwalker.helm plugin. Do not edit.",
                extension, constantsBySourceSet, bundlesBySourceSet);
    }

    private TaskProvider<HelmPackageTask> registerChart(Project project, HelmExtension extension,
            HelmChart chart) {
        String name = chart.getName();
        boolean lifecycle = LifecycleSupport.enabled(
                chart.getLifecycleIntegration(), extension.getLifecycleIntegration());
        ProjectLayout layout = project.getLayout();
        Provider<Directory> stagedDir = layout.getBuildDirectory().dir(HelmChart.stagedChartPath(name));

        // Assemble the chart plus its resolved subchart archives into a staging dir
        // (injecting build-time pre-values), keeping the user's source tree pristine
        // and handling subcharts uniformly. Downstream tasks read the staged copy.
        var stageTask = project.getTasks().register(HelmChart.stageTaskName(name), HelmStageTask.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Stages the '" + name + "' chart and its subchart dependencies.");
            t.getChartDirectory().convention(chart.getChartDirectory());
            t.getSubchartArchives().from(chart.getSubchartFiles());
            t.getPreValues().convention(chart.getPreValues());
            t.getStagedDirectory().convention(stagedDir);
        });
        Provider<Directory> staged = stageTask.flatMap(HelmStageTask::getStagedDirectory);

        var packageTask = project.getTasks().register(HelmChart.packageTaskName(name), HelmPackageTask.class, t -> {
            t.setDescription("Packages the '" + name + "' chart with helm package.");
            t.getChartDirectory().convention(staged);
            t.getChartVersion().convention(chart.getChartVersion());
            t.getAppVersion().convention(chart.getAppVersion());
            t.getUpdateDependencies().convention(chart.getUpdateDependencies());
            t.getPackagedChart().convention(layout.getBuildDirectory().file(HelmChart.packagedChartPath(name)));
        });

        // Default-on: packaging the chart is part of `assemble` (and so `build`). Opt out per project
        // (extension) or per chart (chart.lifecycleIntegration).
        if (lifecycle) {
            LifecycleSupport.assembleDependsOn(project, packageTask);
        }

        if (chart.getLint().get()) {
            var lintTask = project.getTasks().register(HelmChart.lintTaskName(name), HelmLintTask.class, t -> {
                t.setDescription("Lints the '" + name + "' chart with helm lint.");
                t.getChartDirectory().convention(staged);
            });
            // Default-on: linting is a verification task, wired into `check`.
            if (lifecycle) {
                LifecycleSupport.checkDependsOn(project, lintTask);
            }
        }

        // Package variant: classifier <chart>, free attrs chartName/chartType=package,
        // artifact type tgz with the package task as its build dependency.
        boolean defaultArtifact = chart.getDefaultArtifact().get();
        var packageElements = ArtifactsDependencies.elements(project,
                HelmChart.packageElementsName(name), name,
                Map.of(HelmAttributes.CHART_NAME_KEY, name,
                        HelmAttributes.CHART_TYPE_KEY, HelmAttributes.CHART_TYPE_PACKAGE),
                List.of(new ArtifactSpec(
                        packageTask.flatMap(HelmPackageTask::getPackagedChart),
                        artifact -> {
                            artifact.setType(HelmAttributes.ARTIFACT_TYPE_PACKAGE);
                            artifact.builtBy(packageTask);
                        })),
                defaultArtifact);
        PublishingSupport.addVariants(project, softwareComponentFactory, packageElements.get(),
                defaultArtifact ? "helm chart '" + name + "'" : null);
        return packageTask;
    }
}
