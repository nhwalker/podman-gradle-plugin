package io.github.nhwalker.podman.gradle.dependency;

import org.gradle.api.attributes.Attribute;

/**
 * The custom Gradle {@link Attribute}s used to describe podman image variants in
 * variant-aware dependency resolution, plus their well-known values.
 *
 * <p>These live in a dedicated namespace (rather than reusing the JVM
 * {@code Usage}/{@code Category}/{@code LibraryElements} attributes) so podman
 * image variants can never accidentally match Java ecosystem variants. The
 * {@link #ECOSYSTEM} attribute, required on every podman variant and request, is
 * the structural fence: no JVM variant declares it.
 *
 * <p>Module <em>identity</em> stays at the Gradle project's implicit
 * {@code group:name} coordinate (its default capability); <em>which image</em>
 * inside a module is chosen by {@link #IMAGE_NAME} and <em>which form</em> by
 * {@link #IMAGE_TYPE}/{@link #ARCHIVE_FORMAT} — the same "one module, several
 * classifier/attribute-selected variants" pattern the Java plugin uses for its
 * sources and javadoc jars.
 */
public final class PodmanAttributes {

    private PodmanAttributes() {
    }

    /**
     * Isolation marker carried by every podman variant and every podman request.
     * Its sole value is {@link #ECOSYSTEM_VALUE}.
     */
    public static final Attribute<String> ECOSYSTEM =
            Attribute.of("io.github.nhwalker.podman.ecosystem", String.class);

    /** Selects which image (by name) within a module that publishes several. */
    public static final Attribute<String> IMAGE_NAME =
            Attribute.of("io.github.nhwalker.podman.imageName", String.class);

    /** Distinguishes the lightweight reference from the exported archive. */
    public static final Attribute<String> IMAGE_TYPE =
            Attribute.of("io.github.nhwalker.podman.imageType", String.class);

    /** The archive container format; only set on {@link #IMAGE_TYPE_ARCHIVE} variants. */
    public static final Attribute<String> ARCHIVE_FORMAT =
            Attribute.of("io.github.nhwalker.podman.archiveFormat", String.class);

    /** Optional target platform, e.g. {@code linux/amd64}; set only when specified. */
    public static final Attribute<String> PLATFORM =
            Attribute.of("io.github.nhwalker.podman.platform", String.class);

    /** The only valid value of {@link #ECOSYSTEM}. */
    public static final String ECOSYSTEM_VALUE = "podman-image";

    /** {@link #IMAGE_TYPE} value for the image reference (coordinate pointer). */
    public static final String IMAGE_TYPE_REFERENCE = "reference";

    /** {@link #IMAGE_TYPE} value for the exported archive (tar bytes). */
    public static final String IMAGE_TYPE_ARCHIVE = "archive";

    /** {@link #ARCHIVE_FORMAT} value for an OCI archive. */
    public static final String ARCHIVE_FORMAT_OCI = "oci-archive";

    /** {@link #ARCHIVE_FORMAT} value for a docker archive. */
    public static final String ARCHIVE_FORMAT_DOCKER = "docker-archive";
}
