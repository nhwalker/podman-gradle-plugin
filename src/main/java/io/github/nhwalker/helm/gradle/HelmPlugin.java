package io.github.nhwalker.helm.gradle;

import javax.inject.Inject;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;

import io.github.nhwalker.helm.gradle.dependency.HelmDependencies;
import io.github.nhwalker.helm.gradle.dsl.HelmChart;
import io.github.nhwalker.helm.gradle.tasks.AbstractHelmTask;
import io.github.nhwalker.helm.gradle.tasks.HelmLintTask;
import io.github.nhwalker.helm.gradle.tasks.HelmPackageTask;

/**
 * Registers the {@code helm} extension, applies its configuration as conventions
 * to every helm task, and turns each declared chart into stage/package/lint tasks
 * plus the consumable configuration and software-component variant used to share
 * packaged charts as dependencies between projects.
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

        project.getTasks().withType(AbstractHelmTask.class).configureEach(task -> {
            task.setGroup(TASK_GROUP);
            task.getExecutable().convention(extension.getExecutable());
            task.getGlobalOptions().convention(extension.getGlobalOptions());
        });

        HelmDependencies.registerSchema(project);

        // One component aggregates every chart's variants (one module/coordinate),
        // the same way the java component carries the main + sources/javadoc jars.
        AdhocComponentWithVariants component = softwareComponentFactory.adhoc(COMPONENT_NAME);
        project.getComponents().add(component);

        // Materialize each chart's tasks/configs once the DSL is fully evaluated, so
        // structural decisions (e.g. whether a lint task exists) see final values.
        project.afterEvaluate(p -> extension.getCharts().forEach(chart -> registerChart(p, chart, component)));
    }

    private void registerChart(Project project, HelmChart chart, AdhocComponentWithVariants component) {
        String name = chart.getName();
        ProjectLayout layout = project.getLayout();
        Provider<Directory> stagedDir = layout.getBuildDirectory().dir(HelmChart.stagedChartPath(name));

        // Assemble the chart plus its resolved subchart archives into a staging dir,
        // keeping the user's source tree pristine and handling subcharts uniformly.
        var stageTask = project.getTasks().register(HelmChart.stageTaskName(name), Sync.class, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription("Stages the '" + name + "' chart and its subchart dependencies.");
            t.into(stagedDir);
            t.from(chart.getChartDirectory());
            t.from(chart.getSubchartFiles(), spec -> spec.into("charts"));
        });

        var packageTask = project.getTasks().register(HelmChart.packageTaskName(name), HelmPackageTask.class, t -> {
            t.setDescription("Packages the '" + name + "' chart with helm package.");
            t.dependsOn(stageTask);
            t.getChartDirectory().convention(stagedDir);
            t.getChartVersion().convention(chart.getChartVersion());
            t.getAppVersion().convention(chart.getAppVersion());
            t.getUpdateDependencies().convention(chart.getUpdateDependencies());
            t.getPackagedChart().convention(layout.getBuildDirectory().file(HelmChart.packagedChartPath(name)));
        });

        if (chart.getLint().get()) {
            project.getTasks().register(HelmChart.lintTaskName(name), HelmLintTask.class, t -> {
                t.setDescription("Lints the '" + name + "' chart with helm lint.");
                t.dependsOn(stageTask);
                t.getChartDirectory().convention(stagedDir);
            });
        }

        var packageElements = HelmDependencies.packageElements(project,
                HelmChart.packageElementsName(name), name,
                packageTask.flatMap(HelmPackageTask::getPackagedChart), packageTask);
        component.addVariantsFromConfiguration(packageElements.get(), details -> { });
    }
}
