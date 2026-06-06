package io.github.nhwalker.helm.gradle.dependency;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConsumableConfiguration;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.provider.Provider;

/**
 * Low-level helpers for wiring helm charts into Gradle's variant-aware dependency
 * model. The high-level {@code charts { }} DSL is built on top of these, so manual
 * users can reproduce the same configurations without the DSL.
 *
 * <ul>
 *   <li>{@link #registerSchema(Project)} — register the {@link HelmAttributes} in
 *       the project's attribute schema (idempotent).</li>
 *   <li>{@link #packageElements} — create the consumable configuration a project
 *       exposes for a packaged chart.</li>
 *   <li>{@link #subchartBucket} / {@link #resolvablePackages} — create the
 *       dependency bucket and the resolvable a consumer uses to pull a subchart
 *       archive.</li>
 * </ul>
 */
public final class HelmDependencies {

    private static final String SCHEMA_REGISTERED_KEY = "io.github.nhwalker.helm.schemaRegistered";

    private HelmDependencies() {
    }

    /**
     * Registers the helm attributes in {@code project}'s attribute schema. Safe to
     * call repeatedly; only the first call per project has any effect.
     */
    public static void registerSchema(Project project) {
        ExtraPropertiesExtension extra = project.getExtensions().getExtraProperties();
        if (extra.has(SCHEMA_REGISTERED_KEY)) {
            return;
        }
        extra.set(SCHEMA_REGISTERED_KEY, Boolean.TRUE);

        var schema = project.getDependencies().getAttributesSchema();
        schema.attribute(HelmAttributes.ECOSYSTEM);
        schema.attribute(HelmAttributes.CHART_NAME);
        schema.attribute(HelmAttributes.CHART_TYPE);
    }

    /**
     * Creates a consumable configuration that publishes a chart's packaged archive
     * (attributes {@code ecosystem, chartName, chartType=package}; artifact
     * classifier {@code <chartName>}).
     */
    public static NamedDomainObjectProvider<ConsumableConfiguration> packageElements(
            Project project, String configurationName, String chartName,
            Provider<RegularFile> packagedChart, Object builtBy) {
        return project.getConfigurations().consumable(configurationName, cfg -> {
            cfg.getAttributes().attribute(HelmAttributes.ECOSYSTEM, HelmAttributes.ECOSYSTEM_VALUE);
            cfg.getAttributes().attribute(HelmAttributes.CHART_NAME, chartName);
            cfg.getAttributes().attribute(HelmAttributes.CHART_TYPE, HelmAttributes.CHART_TYPE_PACKAGE);
            cfg.getOutgoing().artifact(packagedChart, artifact -> {
                artifact.setType("tgz");
                artifact.setClassifier(chartName);
                artifact.builtBy(builtBy);
            });
        });
    }

    /** Creates a dependency-scope bucket for declaring subchart dependencies. */
    public static NamedDomainObjectProvider<DependencyScopeConfiguration> subchartBucket(
            Project project, String configurationName) {
        return project.getConfigurations().dependencyScope(configurationName);
    }

    /**
     * Creates a resolvable that resolves the {@code bucket} to packaged chart
     * archives. When {@code chartName} is non-null the request additionally pins
     * {@code chartName}, selecting one chart out of a multi-chart producer.
     */
    public static NamedDomainObjectProvider<ResolvableConfiguration> resolvablePackages(
            Project project, String configurationName,
            NamedDomainObjectProvider<DependencyScopeConfiguration> bucket, String chartName) {
        return project.getConfigurations().resolvable(configurationName, cfg -> {
            cfg.extendsFrom(bucket.get());
            cfg.getAttributes().attribute(HelmAttributes.ECOSYSTEM, HelmAttributes.ECOSYSTEM_VALUE);
            cfg.getAttributes().attribute(HelmAttributes.CHART_TYPE, HelmAttributes.CHART_TYPE_PACKAGE);
            if (chartName != null) {
                cfg.getAttributes().attribute(HelmAttributes.CHART_NAME, chartName);
            }
        });
    }
}
