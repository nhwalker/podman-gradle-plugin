package io.github.nhwalker.container.gradle.dependency;

import org.gradle.api.attributes.AttributeDisambiguationRule;
import org.gradle.api.attributes.MultipleCandidatesDetails;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsAttributes;

/**
 * The well-known attribute keys and values describing container image variants, plus
 * the disambiguation rule that defaults an unspecified archive format.
 *
 * <p>Container images are published and consumed as <em>generic artifacts</em> (see
 * {@link io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies}): module
 * <em>identity</em> stays at the Gradle project's implicit {@code group:name} coordinate
 * (its default capability), the required {@link ArtifactsAttributes#ECOSYSTEM
 * ecosystem=generic-artifact} marker fences the variants off from the JVM ecosystem, and
 * the {@link ArtifactsAttributes#CLASSIFIER classifier} attribute (defaulting each
 * variant's Maven classifier too) plus the free String attributes below refine
 * <em>which image</em> ({@link #IMAGE_NAME_KEY}) and <em>which form</em>
 * ({@link #IMAGE_TYPE_KEY}/{@link #ARCHIVE_FORMAT_KEY}) a request selects.
 *
 * <p>The keys keep the {@code io.github.nhwalker.container.*} namespace so they never
 * collide with the generic core attributes and so a published module's Gradle Module
 * Metadata advertises stable, container-specific attribute names.
 */
public final class ContainerAttributes {

    private ContainerAttributes() {
    }

    /** Free-attribute key selecting which image (by name) within a module that publishes several. */
    public static final String IMAGE_NAME_KEY = "io.github.nhwalker.container.imageName";

    /** Free-attribute key distinguishing the lightweight reference from the exported archive. */
    public static final String IMAGE_TYPE_KEY = "io.github.nhwalker.container.imageType";

    /** Free-attribute key for the archive container format; set only on archive variants. */
    public static final String ARCHIVE_FORMAT_KEY = "io.github.nhwalker.container.archiveFormat";

    /** {@link #IMAGE_TYPE_KEY} value for the image reference (coordinate pointer). */
    public static final String IMAGE_TYPE_REFERENCE = "reference";

    /** {@link #IMAGE_TYPE_KEY} value for the exported archive (tar bytes). */
    public static final String IMAGE_TYPE_ARCHIVE = "archive";

    /** {@link #ARCHIVE_FORMAT_KEY} value for an OCI archive. */
    public static final String ARCHIVE_FORMAT_OCI = "oci-archive";

    /** {@link #ARCHIVE_FORMAT_KEY} value for a docker archive. */
    public static final String ARCHIVE_FORMAT_DOCKER = "docker-archive";

    /**
     * When the consumer does not request an {@code archiveFormat}, default to
     * {@code oci-archive} if it is among the candidates.
     */
    public static final class ArchiveFormatDefaultRule implements AttributeDisambiguationRule<String> {
        @Override
        public void execute(MultipleCandidatesDetails<String> details) {
            if (details.getConsumerValue() == null
                    && details.getCandidateValues().contains(ARCHIVE_FORMAT_OCI)) {
                details.closestMatch(ARCHIVE_FORMAT_OCI);
            }
        }
    }
}
