package io.github.nhwalker.helm.gradle;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import io.github.nhwalker.helm.gradle.dsl.HelmChart;

/**
 * Project-level configuration for the Helm plugin.
 *
 * <p>The {@code executable}/{@code globalOptions} values are used as conventions
 * for every helm task in the project. The {@code charts} container declares charts
 * to lint, package, and share as dependencies:
 *
 * <pre>
 * helm {
 *     executable = '/usr/local/bin/helm'
 *     charts {
 *         api      { chartDirectory = layout.projectDirectory.dir('src/main/helm/api') }
 *         platform { chartDirectory = layout.projectDirectory.dir('src/main/helm/platform'); from project(':api') }
 *     }
 * }
 * </pre>
 */
public abstract class HelmExtension {

    private final NamedDomainObjectContainer<HelmChart> charts;

    @Inject
    public HelmExtension(ObjectFactory objects, Project project) {
        // HelmChart needs the Project to create its dependency configurations, so the
        // container uses a custom element factory rather than Gradle's default.
        this.charts = objects.domainObjectContainer(HelmChart.class,
                name -> objects.newInstance(HelmChart.class, name, project));
    }

    /**
     * The helm executable to invoke. Defaults to {@code "helm"}, resolved against
     * the {@code PATH} of the Gradle process.
     */
    public abstract Property<String> getExecutable();

    /**
     * Global options inserted immediately after the executable and before the
     * subcommand on every invocation (for example {@code ["--namespace", "platform"]}
     * or {@code ["--kube-context", "staging"]}). Empty by default.
     */
    public abstract ListProperty<String> getGlobalOptions();

    /**
     * When {@code true} and a Java plugin is applied, the plugin bundles each packaged
     * chart into the jar at {@code charts/<chart>.tgz} and generates a
     * {@code <ProjectName>Charts} Java interface exposing those resource paths as
     * {@code public static final String} constants named after the chart in
     * UPPER_SNAKE_CASE. The interface is added to the {@code main} source set and
     * (re)generated whenever a chart is packaged. Defaults to {@code false}.
     */
    public abstract Property<Boolean> getGenerateJavaRefs();

    /**
     * The package the generated {@code <ProjectName>Charts} interface is placed into.
     * Defaults to the project group; when blank the default package is used.
     */
    public abstract Property<String> getJavaRefsPackage();

    /** The charts declared for this project. */
    public NamedDomainObjectContainer<HelmChart> getCharts() {
        return charts;
    }

    /** Configures the {@link #getCharts() charts} container. */
    public void charts(Action<? super NamedDomainObjectContainer<HelmChart>> action) {
        action.execute(charts);
    }
}
