package io.github.nhwalker.artifacts.gradle.dependency;

import org.gradle.api.attributes.Attribute;

/**
 * The custom Gradle {@link Attribute}s used to describe generic artifact variants in
 * variant-aware dependency resolution, plus their well-known values.
 *
 * <p>These live in a dedicated namespace (rather than reusing the JVM
 * {@code Usage}/{@code Category}/{@code LibraryElements} attributes) so generic
 * artifact variants can never accidentally match Java ecosystem variants. The
 * {@link #ECOSYSTEM} attribute, required on every variant and every request, is the
 * structural fence: no JVM variant declares it.
 *
 * <p>Module <em>identity</em> stays at the Gradle project's implicit
 * {@code group:name} coordinate (its default capability); <em>which artifact</em>
 * inside a module is chosen by {@link #CLASSIFIER} together with any number of
 * user-declared free String attributes. This is the Maven "classifier" idea modeled
 * as a Gradle attribute, which (unlike a Maven classifier) survives composite-build
 * substitution and Gradle Module Metadata variant selection.
 */
public final class ArtifactsAttributes {

    private ArtifactsAttributes() {
    }

    /**
     * Isolation marker carried by every generic artifact variant and every request.
     * Its sole value is {@link #ECOSYSTEM_VALUE}.
     */
    public static final Attribute<String> ECOSYSTEM =
            Attribute.of("io.github.nhwalker.artifacts.ecosystem", String.class);

    /** Selects which artifact (by classifier) within a module that publishes several. */
    public static final Attribute<String> CLASSIFIER =
            Attribute.of("io.github.nhwalker.artifacts.classifier", String.class);

    /** The only valid value of {@link #ECOSYSTEM}. */
    public static final String ECOSYSTEM_VALUE = "generic-artifact";

    /** Builds the {@link Attribute} for a user-declared free String attribute key. */
    public static Attribute<String> freeAttribute(String key) {
        return Attribute.of(key, String.class);
    }
}
