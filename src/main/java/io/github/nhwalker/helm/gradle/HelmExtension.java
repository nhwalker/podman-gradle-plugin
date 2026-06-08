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
     * The package the generated {@code <ProjectName>Charts} interface is placed into.
     * Defaults to the project group; when blank the default package is used.
     *
     * <p>The interface is generated, per source set, whenever a chart is
     * {@link io.github.nhwalker.helm.gradle.dsl.HelmChart#importResourcesTask() bundled into resources}
     * and the {@code java} plugin is applied. Each bundled chart contributes a
     * {@code public static final String} constant (named after the chart in UPPER_SNAKE_CASE) holding
     * its {@code charts/<chart>.tgz} resource path.
     */
    public abstract Property<String> getReferencesPackage();

    /**
     * The name of the generated interface for the {@code main} source set. Defaults to
     * {@code <ProjectName>Charts}; override to customize (e.g. {@code 'MyCharts'}). Charts bundled
     * into a non-{@code main} source set append the capitalized source-set name (e.g.
     * {@code MyChartsTest}).
     */
    public abstract Property<String> getReferencesClassName();

    /**
     * Whether declared charts participate in the standard lifecycle tasks by default: when {@code true}
     * (the default) {@code assemble} (and therefore {@code build}) packages every chart and {@code check}
     * lints every chart whose {@link io.github.nhwalker.helm.gradle.dsl.HelmChart#getLint() lint} is on.
     * Set to {@code false} to opt the whole project out; individual charts can override either way via
     * {@link io.github.nhwalker.helm.gradle.dsl.HelmChart#getLifecycleIntegration()}.
     */
    public abstract Property<Boolean> getLifecycleIntegration();

    /** The charts declared for this project. */
    public NamedDomainObjectContainer<HelmChart> getCharts() {
        return charts;
    }

    /** Configures the {@link #getCharts() charts} container. */
    public void charts(Action<? super NamedDomainObjectContainer<HelmChart>> action) {
        action.execute(charts);
    }
}
