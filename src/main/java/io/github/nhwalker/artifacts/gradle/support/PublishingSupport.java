package io.github.nhwalker.artifacts.gradle.support;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.plugins.ExtraPropertiesExtension;

/**
 * Shared helper that funnels every container/helm/generic-artifacts variant into a single publishable
 * software component, so a project applying any mix of those plugins (with or without {@code java})
 * publishes <strong>one coherent module</strong> per coordinate.
 *
 * <p>Each call to {@link #addVariants} attaches a consumable configuration's variants to:
 * <ul>
 *   <li>the aggregate {@link #AGGREGATE_COMPONENT_NAME} component (always; created lazily and shared
 *       across the three plugins via {@link ExtraPropertiesExtension}), which carries only the
 *       image/chart/artifact variants — never the jar/sources/javadoc; and</li>
 *   <li>{@code components.java} as well, when the {@code java} plugin is applied, so a single
 *       {@code from components.java} ships the jar plus the images/charts/artifacts.</li>
 * </ul>
 * Both components resolve to the same {@code group:name:version}, so a project publishes only one of
 * them per repository.
 *
 * <p>An artifact may also be designated the module's <em>default</em> (unclassified) artifact via
 * {@code defaultArtifactSource}; see {@link #addVariants(Project, SoftwareComponentFactory,
 * Configuration, String)}.
 */
public final class PublishingSupport {

    /** The name of the software component aggregating this project's image/chart/artifact variants. */
    public static final String AGGREGATE_COMPONENT_NAME = "genericArtifacts";

    private static final String COMPONENT_KEY = "io.github.nhwalker.artifacts.aggregateComponent";
    private static final String DEFAULT_ARTIFACT_KEY = "io.github.nhwalker.artifacts.defaultArtifactSource";

    private PublishingSupport() {
    }

    /**
     * Eagerly creates the aggregate {@link #AGGREGATE_COMPONENT_NAME} component if it does not yet exist,
     * so {@code components.genericArtifacts} is resolvable during build-script evaluation. Idempotent
     * across the container/helm/artifacts plugins (the first to apply creates it); call from {@code apply}.
     */
    public static void registerComponent(Project project, SoftwareComponentFactory factory) {
        aggregate(project, factory);
    }

    /** Attaches {@code elements}' variants to the shared components without designating a default artifact. */
    public static void addVariants(Project project, SoftwareComponentFactory factory, Configuration elements) {
        addVariants(project, factory, elements, null);
    }

    /**
     * Attaches {@code elements}' variants to the aggregate component (and to {@code components.java} when
     * the {@code java} plugin is applied). When {@code defaultArtifactSource} is non-null this variant's
     * artifact has been published without a Maven classifier (its caller cleared it), making it the
     * module's default artifact: at most one such designation is allowed per project — a second throws an
     * {@link InvalidUserDataException} naming both sources — and when {@code java} is applied a warning is
     * logged because the jar remains the primary artifact of {@code components.java}.
     *
     * @param defaultArtifactSource a human-readable description of the designating element (e.g.
     *        {@code "helm chart 'app'"}), or {@code null} when this is an ordinary classified variant.
     */
    public static void addVariants(Project project, SoftwareComponentFactory factory,
            Configuration elements, String defaultArtifactSource) {
        if (defaultArtifactSource != null) {
            registerDefaultArtifact(project, defaultArtifactSource);
        }
        aggregate(project, factory).addVariantsFromConfiguration(elements, details -> { });
        if (project.getPluginManager().hasPlugin("java")) {
            AdhocComponentWithVariants java =
                    (AdhocComponentWithVariants) project.getComponents().getByName("java");
            java.addVariantsFromConfiguration(elements, details -> { });
        }
    }

    private static void registerDefaultArtifact(Project project, String source) {
        ExtraPropertiesExtension extra = project.getExtensions().getExtraProperties();
        if (extra.has(DEFAULT_ARTIFACT_KEY)) {
            throw new InvalidUserDataException("Only one artifact may be the module's default (unclassified)"
                    + " artifact, but both " + extra.get(DEFAULT_ARTIFACT_KEY) + " and " + source
                    + " are designated. Mark only one with defaultArtifact.");
        }
        extra.set(DEFAULT_ARTIFACT_KEY, source);
        if (project.getPluginManager().hasPlugin("java")) {
            project.getLogger().warn("The 'java' plugin is applied, so the jar remains the module's primary"
                    + " (unclassified) artifact when publishing 'components.java'; " + source
                    + " is honored as the default artifact only when publishing 'components."
                    + AGGREGATE_COMPONENT_NAME + "'.");
        }
    }

    private static AdhocComponentWithVariants aggregate(Project project, SoftwareComponentFactory factory) {
        ExtraPropertiesExtension extra = project.getExtensions().getExtraProperties();
        if (!extra.has(COMPONENT_KEY)) {
            AdhocComponentWithVariants component = factory.adhoc(AGGREGATE_COMPONENT_NAME);
            project.getComponents().add(component);
            extra.set(COMPONENT_KEY, component);
        }
        return (AdhocComponentWithVariants) extra.get(COMPONENT_KEY);
    }
}
