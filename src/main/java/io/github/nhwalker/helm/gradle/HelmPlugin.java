package io.github.nhwalker.helm.gradle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactSpec;
import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies;
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
 * {@link HelmChart#importResourcesTask()} (mirroring the generic artifacts DSL); enabling
 * {@code generateReferences} additionally generates a {@code <ProjectName>References} Java interface
 * (per source set) exposing the bundled charts' resource paths.
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

    /** The name of the software component aggregating this project's chart variants. */
    public static final String COMPONENT_NAME = "helm";

    /** The task that generates the {@code main} source set's {@code <ProjectName>Charts} interface. */
    public static final String GENERATE_REFERENCES_TASK = "generateChartReferences";

    /** The {@code <Domain>} segment of this plugin's generated interface name. */
    public static final String REFERENCES_DOMAIN = "Charts";

    /**
     * The references task name for a source set: {@link #GENERATE_REFERENCES_TASK} for {@code main},
     * {@code generate<SourceSet>ChartReferences} otherwise.
     */
    public static String generateReferencesTaskName(String sourceSetName) {
        if (SourceSet.MAIN_SOURCE_SET_NAME.equals(sourceSetName)) {
            return GENERATE_REFERENCES_TASK;
        }
        return "generate" + Character.toUpperCase(sourceSetName.charAt(0)) + sourceSetName.substring(1)
                + "ChartReferences";
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
        extension.getGenerateReferences().convention(false);
        extension.getReferencesPackage().convention(
                project.provider(() -> String.valueOf(project.getGroup())));
        extension.getReferencesClassName().convention(project.provider(() ->
                ResourceImports.defaultReferencesBaseName(project.getName(), REFERENCES_DOMAIN)));

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

        // One component aggregates every chart's variants (one module/coordinate),
        // the same way the java component carries the main + sources/javadoc jars.
        AdhocComponentWithVariants component = softwareComponentFactory.adhoc(COMPONENT_NAME);
        project.getComponents().add(component);

        // Materialize each chart's tasks/configs once the DSL is fully evaluated, so
        // structural decisions (e.g. whether a lint task exists) see final values.
        project.afterEvaluate(p -> {
            extension.getCharts().forEach(chart -> registerChart(p, chart, component));
            // When opted in and a Java plugin is applied, expose the resource paths of the charts
            // that bundled themselves into resources through a generated interface.
            if (extension.getGenerateReferences().get() && p.getPluginManager().hasPlugin("java")) {
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
        constantsBySourceSet.forEach((sourceSet, constants) -> {
            String className = ResourceImports.withSourceSetSuffix(
                    extension.getReferencesClassName().get(), sourceSet);
            Provider<Directory> output = project.getLayout().getBuildDirectory()
                    .dir("generated/sources/helmChartRefs/java/" + sourceSet);
            ResourceImports.generateReferences(project, TASK_GROUP, generateReferencesTaskName(sourceSet),
                    className, extension.getReferencesPackage(),
                    "Generated by the io.github.nhwalker.helm plugin. Do not edit.",
                    constants, output, sourceSet, bundlesBySourceSet.get(sourceSet));
        });
    }

    private TaskProvider<HelmPackageTask> registerChart(Project project, HelmChart chart,
            AdhocComponentWithVariants component) {
        String name = chart.getName();
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

        if (chart.getLint().get()) {
            project.getTasks().register(HelmChart.lintTaskName(name), HelmLintTask.class, t -> {
                t.setDescription("Lints the '" + name + "' chart with helm lint.");
                t.getChartDirectory().convention(staged);
            });
        }

        // Package variant: classifier <chart>, free attrs chartName/chartType=package,
        // artifact type tgz with the package task as its build dependency.
        var packageElements = ArtifactsDependencies.elements(project,
                HelmChart.packageElementsName(name), name,
                Map.of(HelmAttributes.CHART_NAME_KEY, name,
                        HelmAttributes.CHART_TYPE_KEY, HelmAttributes.CHART_TYPE_PACKAGE),
                List.of(new ArtifactSpec(
                        packageTask.flatMap(HelmPackageTask::getPackagedChart),
                        artifact -> {
                            artifact.setType("tgz");
                            artifact.builtBy(packageTask);
                        })));
        component.addVariantsFromConfiguration(packageElements.get(), details -> { });
        return packageTask;
    }
}
