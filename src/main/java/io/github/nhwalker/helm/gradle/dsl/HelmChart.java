package io.github.nhwalker.helm.gradle.dsl;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies;
import io.github.nhwalker.artifacts.gradle.support.ResourceImports;
import io.github.nhwalker.helm.gradle.HelmPlugin;
import io.github.nhwalker.helm.gradle.dependency.HelmAttributes;

/**
 * A single chart declared in the {@code helm { charts { } }} container.
 *
 * <p>For each chart the plugin registers a stage task (assembling the chart and
 * its subchart dependencies), a package task, an optional lint task, and the
 * consumable configuration other projects resolve against.
 *
 * <p>Use {@code from(...)} to declare a subchart dependency; the resolved chart
 * archive is staged into this chart's {@code charts/} directory before packaging,
 * and the producer is built first:
 * <pre>
 * helm { charts {
 *     base { chartDirectory = layout.projectDirectory.dir('charts/base') }
 *     umbrella {
 *         chartDirectory = layout.projectDirectory.dir('charts/umbrella')
 *         from charts.base                              // intra-project
 *         // or: from project(':other')                 // same build, another project
 *         // or: from 'com.example:billing-chart:1.4.0' // external / composite
 *         // or: from(project(':services'), 'api')      // pin one chart of a multi-chart producer
 *     }
 * } }
 * </pre>
 */
public abstract class HelmChart implements Named {

    private final String name;
    private final Project project;
    private int subchartCounter;
    // The first bundling task registered for each target source set (insertion-ordered).
    private final Map<String, TaskProvider<Sync>> resourceBundles = new LinkedHashMap<>();

    @Inject
    @SuppressWarnings("this-escape")
    public HelmChart(String name, Project project) {
        this.name = name;
        this.project = project;
        getChartDirectory().convention(
                project.getLayout().getProjectDirectory().dir("src/main/helm/" + name));
        getLint().convention(true);
        getUpdateDependencies().convention(false);
    }

    @Override
    public String getName() {
        return name;
    }

    /** The chart directory containing {@code Chart.yaml}. Defaults to {@code src/main/helm/<name>}. */
    public abstract DirectoryProperty getChartDirectory();

    /** Override the chart version ({@code helm package --version}). */
    public abstract Property<String> getChartVersion();

    /** Override the chart {@code appVersion} ({@code helm package --app-version}). */
    public abstract Property<String> getAppVersion();

    /** Whether to register a lint task for this chart. Defaults to {@code true}. */
    public abstract Property<Boolean> getLint();

    /** Update {@code Chart.yaml} dependencies before packaging ({@code -u}). Defaults to {@code false}. */
    public abstract Property<Boolean> getUpdateDependencies();

    /**
     * Whether this chart participates in the standard lifecycle tasks (its package task becomes a
     * dependency of {@code assemble}, and its lint task — when {@link #getLint() lint} is on — a
     * dependency of {@code check}). Unset by default, inheriting the project-wide
     * {@link io.github.nhwalker.helm.gradle.HelmExtension#getLifecycleIntegration()} (which defaults to
     * {@code true}); set explicitly to opt this chart in or out regardless of the project default.
     */
    public abstract Property<Boolean> getLifecycleIntegration();

    /**
     * Build-time values injected before packaging. Each entry replaces the
     * placeholder <code>{{ .PreValues.&lt;key&gt; }}</code> (whitespace inside the
     * braces is ignored) wherever it appears in the chart's {@code Chart.yaml} and
     * {@code values.yaml}:
     * <pre>
     * helm { charts { api {
     *     preValues = ['ChartVersion': project.version.toString(), 'AppTag': 'abc123']
     * } } }
     * </pre>
     * with {@code Chart.yaml} containing {@code version: {{ .PreValues.ChartVersion }}}.
     */
    public abstract MapProperty<String, String> getPreValues();

    /** The resolved subchart archives declared via {@code from(...)}. Staged into {@code charts/}. */
    public abstract ConfigurableFileCollection getSubchartFiles();

    // ---- subchart declarations --------------------------------------------------

    /** Declares a subchart dependency from another project or an external coordinate. */
    public void from(Object dependencyNotation) {
        from(dependencyNotation, null);
    }

    /**
     * Declares a subchart dependency, selecting a specific chart by name out of a
     * producer that publishes several under one coordinate.
     */
    public void from(Object dependencyNotation, String chartName) {
        String token = "Subchart" + (subchartCounter++);
        NamedDomainObjectProvider<DependencyScopeConfiguration> bucket =
                ArtifactsDependencies.dependencyBucket(project, name + "Dep" + token);
        project.getDependencies().add(bucket.getName(), dependencyNotation);

        // Select a packaged chart: pin chartType=package (and chartName when choosing one
        // chart out of several). No classifier and no ecosystem fence, so the request
        // also resolves cleanly across projects and composite builds.
        Map<String, String> request = chartName == null
                ? Map.of(HelmAttributes.CHART_TYPE_KEY, HelmAttributes.CHART_TYPE_PACKAGE)
                : Map.of(HelmAttributes.CHART_TYPE_KEY, HelmAttributes.CHART_TYPE_PACKAGE,
                        HelmAttributes.CHART_NAME_KEY, chartName);
        NamedDomainObjectProvider<ResolvableConfiguration> resolvable =
                ArtifactsDependencies.resolvable(project, name + "Refs" + token, bucket, null, request);
        getSubchartFiles().from(resolvable);
    }

    /** Declares a subchart that is a sibling chart in the same project. */
    public void from(HelmChart sibling) {
        getSubchartFiles().from(
                project.getLayout().getBuildDirectory().file(packagedChartPath(sibling.getName())));
        getSubchartFiles().builtBy(packageTaskName(sibling.getName()));
    }

    /** Alias for {@link #from(Object)}. */
    public void subchart(Object dependencyNotation) {
        from(dependencyNotation);
    }

    // ---- resource bundling ------------------------------------------------------

    /**
     * Registers (or returns) a {@code Sync} task that bundles this chart's packaged archive into
     * the {@code main} source set's resources under {@code charts/}, so it ships in the jar. Safe
     * to call anywhere as a dependency handle.
     */
    public TaskProvider<Sync> importResourcesTask() {
        return importResourcesTask(SourceSet.MAIN_SOURCE_SET_NAME, null);
    }

    /**
     * Registers a {@code Sync} task that bundles this chart's packaged archive into the {@code main}
     * source set's resources. See {@link #importResourcesTask(String, Action)}.
     */
    public TaskProvider<Sync> importResourcesTask(Action<? super CopySpec> configuration) {
        return importResourcesTask(SourceSet.MAIN_SOURCE_SET_NAME, configuration);
    }

    /**
     * Bundles this chart's packaged archive into the named source set's resources. See
     * {@link #importResourcesTask(String, Action)}.
     */
    public TaskProvider<Sync> importResourcesTask(String sourceSetName) {
        return importResourcesTask(sourceSetName, null);
    }

    /**
     * Registers a {@code Sync} task that bundles this chart's packaged archive into the named source
     * set's resources at {@code charts/<chart>.tgz}, so it is carried in the jar and visible on the
     * eclipse classpath (the target project must apply the {@code java} plugin). Bundling a chart also
     * contributes a constant (its {@code charts/<chart>.tgz} resource path) to the generated
     * {@code <ProjectName>Charts} interface for its source set. {@code configuration} further
     * configures the copy spec; the {@code charts/} prefix is applied first.
     *
     * <p>The first call registers the task and applies {@code configuration}; later calls return the
     * same {@code TaskProvider} as an idempotent dependency handle.
     */
    public TaskProvider<Sync> importResourcesTask(String sourceSetName, Action<? super CopySpec> configuration) {
        Provider<Directory> destination = project.getLayout().getBuildDirectory()
                .dir("generated/resources/helmCharts/" + name + "/" + sourceSetName);
        Object packagedChart = project.getLayout().getBuildDirectory().file(packagedChartPath(name));
        Action<CopySpec> placement = spec -> {
            spec.into("charts");
            if (configuration != null) {
                configuration.execute(spec);
            }
        };
        TaskProvider<Sync> task = ResourceImports.register(project, HelmPlugin.TASK_GROUP,
                importResourcesTaskName(name, sourceSetName),
                "Bundles the packaged '" + name + "' chart into the '" + sourceSetName + "' resources.",
                packageTaskName(name), packagedChart, sourceSetName, destination, placement);
        resourceBundles.putIfAbsent(sourceSetName, task);
        return task;
    }

    /**
     * The bundling tasks registered by {@link #importResourcesTask}, keyed by target source set
     * name (empty if this chart is not bundled into resources). Read by the plugin to build the
     * per-source-set references interface.
     */
    public Map<String, TaskProvider<Sync>> getResourceBundles() {
        return resourceBundles;
    }

    // ---- naming helpers (shared with the plugin reaction) -----------------------

    public static String importResourcesTaskName(String chart, String sourceSetName) {
        return "import" + capitalize(chart) + sourceSetQualifier(sourceSetName) + "ChartResources";
    }

    /** Empty for the conventional {@code main} source set, otherwise the capitalized name. */
    private static String sourceSetQualifier(String sourceSetName) {
        return SourceSet.MAIN_SOURCE_SET_NAME.equals(sourceSetName) ? "" : capitalize(sourceSetName);
    }

    public static String stageTaskName(String chart) {
        return "stage" + capitalize(chart) + "Chart";
    }

    public static String packageTaskName(String chart) {
        return "package" + capitalize(chart) + "Chart";
    }

    public static String lintTaskName(String chart) {
        return "lint" + capitalize(chart) + "Chart";
    }

    public static String packageElementsName(String chart) {
        return chart + "PackageElements";
    }

    /** The build-relative directory the chart is staged into before packaging. */
    public static String stagedChartPath(String chart) {
        return "helm/" + chart + "/staged";
    }

    /** The build-relative path of a chart's packaged archive. */
    public static String packagedChartPath(String chart) {
        return "helm/" + chart + "/" + chart + ".tgz";
    }

    /** The classpath resource path the packaged chart is bundled at inside the jar. */
    public static String jarResourcePath(String chart) {
        return "charts/" + chart + ".tgz";
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
