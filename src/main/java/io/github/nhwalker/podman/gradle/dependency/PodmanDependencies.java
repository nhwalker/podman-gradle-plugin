package io.github.nhwalker.podman.gradle.dependency;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConsumableConfiguration;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.attributes.AttributeDisambiguationRule;
import org.gradle.api.attributes.MultipleCandidatesDetails;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.provider.Provider;

/**
 * Low-level helpers for wiring podman images into Gradle's variant-aware
 * dependency model. The high-level {@code images { }} DSL is built on top of
 * these, so manual users can reproduce the same configurations without the DSL.
 *
 * <ul>
 *   <li>{@link #registerSchema(Project)} — register the {@link PodmanAttributes}
 *       in the project's attribute schema (idempotent).</li>
 *   <li>{@link #referenceElements} / {@link #archiveElements} — create the
 *       consumable configurations a project exposes for an image.</li>
 *   <li>{@link #baseImageBucket} / {@link #resolvableReferences} — create the
 *       dependency bucket and the resolvable a consumer uses to pull a base
 *       image's reference.</li>
 * </ul>
 */
public final class PodmanDependencies {

    private static final String SCHEMA_REGISTERED_KEY = "io.github.nhwalker.podman.schemaRegistered";

    private PodmanDependencies() {
    }

    /**
     * Registers the podman attributes in {@code project}'s attribute schema and
     * installs the {@code archiveFormat} default rule. Safe to call repeatedly;
     * only the first call per project has any effect.
     */
    public static void registerSchema(Project project) {
        ExtraPropertiesExtension extra = project.getExtensions().getExtraProperties();
        if (extra.has(SCHEMA_REGISTERED_KEY)) {
            return;
        }
        extra.set(SCHEMA_REGISTERED_KEY, Boolean.TRUE);

        var schema = project.getDependencies().getAttributesSchema();
        schema.attribute(PodmanAttributes.ECOSYSTEM);
        schema.attribute(PodmanAttributes.IMAGE_NAME);
        schema.attribute(PodmanAttributes.IMAGE_TYPE);
        schema.attribute(PodmanAttributes.PLATFORM);
        schema.attribute(PodmanAttributes.ARCHIVE_FORMAT)
                .getDisambiguationRules().add(ArchiveFormatDefaultRule.class);
    }

    /**
     * Creates a consumable configuration that publishes an image's reference
     * file (attributes {@code ecosystem, imageName, imageType=reference}; artifact
     * classifier {@code <imageName>-reference}).
     */
    public static NamedDomainObjectProvider<ConsumableConfiguration> referenceElements(
            Project project, String configurationName, String imageName,
            Provider<RegularFile> referenceFile, Object builtBy) {
        return project.getConfigurations().consumable(configurationName, cfg -> {
            cfg.getAttributes().attribute(PodmanAttributes.ECOSYSTEM, PodmanAttributes.ECOSYSTEM_VALUE);
            cfg.getAttributes().attribute(PodmanAttributes.IMAGE_NAME, imageName);
            cfg.getAttributes().attribute(PodmanAttributes.IMAGE_TYPE, PodmanAttributes.IMAGE_TYPE_REFERENCE);
            cfg.getOutgoing().artifact(referenceFile, artifact -> {
                artifact.setType("txt");
                artifact.setClassifier(imageName + "-reference");
                artifact.builtBy(builtBy);
            });
        });
    }

    /**
     * Creates a consumable configuration that publishes an image's exported
     * archive (attributes {@code ecosystem, imageName, imageType=archive,
     * archiveFormat}; artifact classifier {@code <imageName>}).
     */
    public static NamedDomainObjectProvider<ConsumableConfiguration> archiveElements(
            Project project, String configurationName, String imageName, String archiveFormat,
            Provider<RegularFile> archiveFile, Object builtBy) {
        return project.getConfigurations().consumable(configurationName, cfg -> {
            cfg.getAttributes().attribute(PodmanAttributes.ECOSYSTEM, PodmanAttributes.ECOSYSTEM_VALUE);
            cfg.getAttributes().attribute(PodmanAttributes.IMAGE_NAME, imageName);
            cfg.getAttributes().attribute(PodmanAttributes.IMAGE_TYPE, PodmanAttributes.IMAGE_TYPE_ARCHIVE);
            cfg.getAttributes().attribute(PodmanAttributes.ARCHIVE_FORMAT, archiveFormat);
            cfg.getOutgoing().artifact(archiveFile, artifact -> {
                artifact.setType("tar");
                artifact.setClassifier(imageName);
                artifact.builtBy(builtBy);
            });
        });
    }

    /** Creates a dependency-scope bucket for declaring base-image dependencies. */
    public static NamedDomainObjectProvider<DependencyScopeConfiguration> baseImageBucket(
            Project project, String configurationName) {
        return project.getConfigurations().dependencyScope(configurationName);
    }

    /**
     * Creates a resolvable that resolves the {@code bucket} to base image
     * reference files. When {@code imageName} is non-null the request additionally
     * pins {@code imageName}, selecting one image out of a multi-image producer.
     */
    public static NamedDomainObjectProvider<ResolvableConfiguration> resolvableReferences(
            Project project, String configurationName,
            NamedDomainObjectProvider<DependencyScopeConfiguration> bucket, String imageName) {
        return project.getConfigurations().resolvable(configurationName, cfg -> {
            cfg.extendsFrom(bucket.get());
            cfg.getAttributes().attribute(PodmanAttributes.ECOSYSTEM, PodmanAttributes.ECOSYSTEM_VALUE);
            cfg.getAttributes().attribute(PodmanAttributes.IMAGE_TYPE, PodmanAttributes.IMAGE_TYPE_REFERENCE);
            if (imageName != null) {
                cfg.getAttributes().attribute(PodmanAttributes.IMAGE_NAME, imageName);
            }
        });
    }

    /**
     * When the consumer does not request an {@code archiveFormat}, default to
     * {@code oci-archive} if it is among the candidates.
     */
    public static final class ArchiveFormatDefaultRule implements AttributeDisambiguationRule<String> {
        @Override
        public void execute(MultipleCandidatesDetails<String> details) {
            if (details.getConsumerValue() == null
                    && details.getCandidateValues().contains(PodmanAttributes.ARCHIVE_FORMAT_OCI)) {
                details.closestMatch(PodmanAttributes.ARCHIVE_FORMAT_OCI);
            }
        }
    }
}
