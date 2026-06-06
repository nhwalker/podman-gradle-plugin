package io.github.nhwalker.artifacts.gradle.dependency;

import java.util.List;
import java.util.Map;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConsumableConfiguration;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.plugins.ExtraPropertiesExtension;

/**
 * Low-level helpers for wiring arbitrary artifacts into Gradle's variant-aware
 * dependency model. The high-level {@code genericArtifacts { }} DSL is built on top
 * of these, so manual users can reproduce the same configurations without the DSL.
 *
 * <ul>
 *   <li>{@link #registerSchema(Project)} — register the {@link ArtifactsAttributes}
 *       in the project's attribute schema (idempotent).</li>
 *   <li>{@link #registerAttributeKey(Project, String)} — register a user-declared
 *       free String attribute in the schema (idempotent).</li>
 *   <li>{@link #elements} — create the consumable configuration a project exposes
 *       for one classified artifact (carries the attributes + the outgoing
 *       artifacts, defaulting each artifact's Maven classifier to the classifier
 *       value, and preserving the producing task as a build dependency).</li>
 *   <li>{@link #dependencyBucket} / {@link #resolvable} — create the dependency
 *       bucket and the resolvable a consumer uses to select an artifact by
 *       classifier (+ free attributes).</li>
 * </ul>
 */
public final class ArtifactsDependencies {

    private static final String SCHEMA_REGISTERED_KEY = "io.github.nhwalker.artifacts.schemaRegistered";

    private ArtifactsDependencies() {
    }

    /**
     * Registers the core artifact attributes in {@code project}'s attribute schema.
     * Safe to call repeatedly; only the first call per project has any effect.
     */
    public static void registerSchema(Project project) {
        ExtraPropertiesExtension extra = project.getExtensions().getExtraProperties();
        if (extra.has(SCHEMA_REGISTERED_KEY)) {
            return;
        }
        extra.set(SCHEMA_REGISTERED_KEY, Boolean.TRUE);

        var schema = project.getDependencies().getAttributesSchema();
        schema.attribute(ArtifactsAttributes.ECOSYSTEM);
        schema.attribute(ArtifactsAttributes.CLASSIFIER);
    }

    /**
     * Registers a user-declared free String attribute {@code key} in {@code project}'s
     * attribute schema if it is not already present. Idempotent.
     */
    public static void registerAttributeKey(Project project, String key) {
        var schema = project.getDependencies().getAttributesSchema();
        var attribute = ArtifactsAttributes.freeAttribute(key);
        if (!schema.hasAttribute(attribute)) {
            schema.attribute(attribute);
        }
    }

    /**
     * Creates a consumable configuration that publishes one classified artifact
     * (attributes {@code ecosystem, classifier} plus the given free attributes; each
     * outgoing artifact's Maven classifier defaults to {@code classifier} unless the
     * spec overrides it).
     */
    public static NamedDomainObjectProvider<ConsumableConfiguration> elements(
            Project project, String configurationName, String classifier,
            Map<String, String> freeAttributes, List<ArtifactSpec> artifacts) {
        return project.getConfigurations().consumable(configurationName, cfg -> {
            cfg.getAttributes().attribute(ArtifactsAttributes.ECOSYSTEM, ArtifactsAttributes.ECOSYSTEM_VALUE);
            cfg.getAttributes().attribute(ArtifactsAttributes.CLASSIFIER, classifier);
            applyFreeAttributes(cfg.getAttributes(), freeAttributes);
            for (ArtifactSpec spec : artifacts) {
                cfg.getOutgoing().artifact(spec.getNotation(), artifact -> {
                    // Default the Maven classifier to the variant classifier so the published
                    // artifact is addressable by classifier in a plain Maven repository too.
                    artifact.setClassifier(classifier);
                    if (spec.getConfiguration() != null) {
                        spec.getConfiguration().execute(artifact);
                    }
                });
            }
        });
    }

    /** Creates a dependency-scope bucket for declaring a consumer's artifact dependencies. */
    public static NamedDomainObjectProvider<DependencyScopeConfiguration> dependencyBucket(
            Project project, String configurationName) {
        return project.getConfigurations().dependencyScope(configurationName);
    }

    /**
     * Creates a resolvable that resolves {@code bucket} to the artifact selected by
     * {@code classifier} (+ the given free attributes).
     */
    public static NamedDomainObjectProvider<ResolvableConfiguration> resolvable(
            Project project, String configurationName,
            NamedDomainObjectProvider<DependencyScopeConfiguration> bucket,
            String classifier, Map<String, String> freeAttributes) {
        return project.getConfigurations().resolvable(configurationName, cfg -> {
            cfg.extendsFrom(bucket.get());
            cfg.getAttributes().attribute(ArtifactsAttributes.ECOSYSTEM, ArtifactsAttributes.ECOSYSTEM_VALUE);
            cfg.getAttributes().attribute(ArtifactsAttributes.CLASSIFIER, classifier);
            applyFreeAttributes(cfg.getAttributes(), freeAttributes);
        });
    }

    private static void applyFreeAttributes(AttributeContainer container, Map<String, String> freeAttributes) {
        freeAttributes.forEach((key, value) ->
                container.attribute(ArtifactsAttributes.freeAttribute(key), value));
    }
}
