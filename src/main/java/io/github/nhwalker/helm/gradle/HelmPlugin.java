package io.github.nhwalker.helm.gradle;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.helm.gradle.dependency.HelmDependencies;
import io.github.nhwalker.helm.gradle.dsl.HelmChart;
import io.github.nhwalker.helm.gradle.tasks.AbstractHelmTask;
import io.github.nhwalker.helm.gradle.tasks.GenerateChartReferencesTask;
import io.github.nhwalker.helm.gradle.tasks.HelmLintTask;
import io.github.nhwalker.helm.gradle.tasks.HelmPackageTask;
import io.github.nhwalker.helm.gradle.tasks.HelmStageTask;

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

    /** The task that generates the {@code <ProjectName>Charts} Java interface. */
    public static final String GENERATE_JAVA_REFS_TASK = "generateChartReferences";

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
        extension.getGenerateJavaRefs().convention(false);
        extension.getJavaRefsPackage().convention(
                project.provider(() -> String.valueOf(project.getGroup())));

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
        project.afterEvaluate(p -> {
            List<TaskProvider<HelmPackageTask>> packageTasks = new ArrayList<>();
            extension.getCharts().forEach(chart -> packageTasks.add(registerChart(p, chart, component)));
            // When a Java plugin is applied and the user opted in, bundle the packaged
            // charts into the jar and expose their resource paths to Java code through a
            // generated interface, refreshed on each package.
            if (extension.getGenerateJavaRefs().get() && p.getPluginManager().hasPlugin("java")) {
                registerJavaRefs(p, extension, packageTasks);
            }
        });
    }

    private void registerJavaRefs(Project project, HelmExtension extension,
            List<TaskProvider<HelmPackageTask>> packageTasks) {
        var generateTask = project.getTasks().register(
                GENERATE_JAVA_REFS_TASK, GenerateChartReferencesTask.class, t -> {
                    t.setGroup(TASK_GROUP);
                    t.setDescription("Generates the "
                            + GenerateChartReferencesTask.interfaceName(project.getName())
                            + " interface of packaged chart resource paths.");
                    t.getProjectName().convention(project.getName());
                    t.getPackageName().convention(extension.getJavaRefsPackage());
                    extension.getCharts().forEach(chart ->
                            t.getCharts().put(chart.getName(), HelmChart.jarResourcePath(chart.getName())));
                    t.getOutputDirectory().convention(project.getLayout().getBuildDirectory()
                            .dir("generated/sources/helmChartRefs/java/main"));
                });

        // Compile the generated interface as part of the project's main sources.
        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        main.getJava().srcDir(generateTask.flatMap(GenerateChartReferencesTask::getOutputDirectory));

        // Bundle each packaged chart into the jar under charts/, matching the resource
        // paths the generated interface exposes.
        project.getTasks().named(main.getProcessResourcesTaskName(), Copy.class, t ->
                packageTasks.forEach(pkg -> t.from(pkg.flatMap(HelmPackageTask::getPackagedChart),
                        spec -> spec.into("charts"))));

        // (Re)generate the interface whenever a chart is packaged.
        packageTasks.forEach(pkg -> pkg.configure(t -> t.finalizedBy(generateTask)));

        // With the eclipse plugin, regenerating the classpath packages the charts (which
        // refreshes the refs); depending on the generator too guarantees the generated
        // source folder exists before the .classpath that references it is written.
        project.getPluginManager().withPlugin("eclipse", applied ->
                project.getTasks().named("eclipseClasspath").configure(t -> {
                    t.dependsOn(generateTask);
                    packageTasks.forEach(t::dependsOn);
                }));
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

        var packageElements = HelmDependencies.packageElements(project,
                HelmChart.packageElementsName(name), name,
                packageTask.flatMap(HelmPackageTask::getPackagedChart), packageTask);
        component.addVariantsFromConfiguration(packageElements.get(), details -> { });
        return packageTask;
    }
}
