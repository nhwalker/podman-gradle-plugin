package io.github.nhwalker.container.gradle.dsl;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.SourceSet;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies;
import io.github.nhwalker.artifacts.gradle.support.Names;
import io.github.nhwalker.container.gradle.dependency.ContainerAttributes;

/**
 * A single image declared in the {@code container { images { } }} container.
 *
 * <p>For each image the plugin registers a build task, a reference-writing task,
 * an optional save task, and the consumable configurations other projects resolve
 * against. The image's build configuration mirrors {@code ContainerBuildTask}.
 *
 * <p>Use {@code from(...)} to declare a base image (a {@code FROM} dependency);
 * the resolved reference is injected into this image's build as a
 * {@code --build-arg}, and the base is built first:
 * <pre>
 * container { images {
 *     base { tags = ['example/base:1.0'] }
 *     app  {
 *         tags = ['example/app:1.0']
 *         from 'BASE_IMAGE', images.base                 // intra-project
 *         // or: from 'BASE_IMAGE', project(':other')    // same build, another project
 *         // or: from 'BASE_IMAGE', 'com.example:platform:1.0', 'runtime'  // external / composite
 *     }
 * } }
 * </pre>
 */
public abstract class ContainerImage implements Named {

    /** Default {@code --build-arg} name used by the single-argument {@code from(...)}. */
    public static final String DEFAULT_BASE_ARG = "BASE_IMAGE";

    /** {@link #getDefaultArtifact() defaultArtifact} value selecting the image archive. */
    public static final String DEFAULT_ARTIFACT_ARCHIVE = "archive";

    /** {@link #getDefaultArtifact() defaultArtifact} value selecting the image reference file. */
    public static final String DEFAULT_ARTIFACT_REFERENCE = "reference";

    private final String name;
    private final Project project;
    private final ObjectFactory objects;
    // The source sets this image's reference constant is exposed on (insertion-ordered).
    private final Set<String> javaReferenceSourceSets = new LinkedHashSet<>();

    @Inject
    @SuppressWarnings("this-escape")
    public ContainerImage(String name, Project project) {
        this.name = name;
        this.project = project;
        this.objects = project.getObjects();
        getContextDirectory().convention(project.getLayout().getProjectDirectory());
        getNoCache().convention(false);
        getPull().convention(false);
        getCreateArchive().convention(false);
        getArchiveFormat().convention(ContainerAttributes.ARCHIVE_FORMAT_OCI);
        getIncludeDigest().convention(true);
        getGenerateSbom().convention(false);
        getSbomFormat().convention(ContainerAttributes.SBOM_FORMAT_CYCLONEDX);
    }

    @Override
    public String getName() {
        return name;
    }

    /** Containerfile/Dockerfile for the build ({@code -f}). */
    public abstract RegularFileProperty getContainerfile();

    /** Build context directory. Defaults to the project directory. */
    public abstract DirectoryProperty getContextDirectory();

    /** Image tags ({@code -t}); the first is the canonical coordinate. */
    public abstract ListProperty<String> getTags();

    /** Build arguments ({@code --build-arg KEY=VALUE}). */
    public abstract MapProperty<String, String> getBuildArgs();

    /** Image labels ({@code --label KEY=VALUE}). */
    public abstract MapProperty<String, String> getLabels();

    /** Target platform, e.g. {@code linux/amd64}. */
    public abstract Property<String> getPlatform();

    /** Stage to build in a multi-stage Containerfile ({@code --target}). */
    public abstract Property<String> getTarget();

    /** Disable the build cache. Defaults to {@code false}. */
    public abstract Property<Boolean> getNoCache();

    /** Always attempt to pull newer base images. Defaults to {@code false}. */
    public abstract Property<Boolean> getPull();

    /** Whether to also export an archive variant (podman save). Defaults to {@code false}. */
    public abstract Property<Boolean> getCreateArchive();

    /** Archive format for the archive variant. Defaults to {@code oci-archive}. */
    public abstract Property<String> getArchiveFormat();

    /** Whether the reference file records the digest-pinned form. Defaults to {@code true}. */
    public abstract Property<Boolean> getIncludeDigest();

    /**
     * Whether to generate an SBOM (Software Bill of Materials) variant for this image by scanning
     * its saved archive with Syft (run in a container). Defaults to {@code false}. Enabling this
     * causes the image's archive tar to be produced even when {@link #getCreateArchive() createArchive}
     * is off (the tar is scanned but its archive variant is not published unless {@code createArchive}
     * is also set).
     */
    public abstract Property<Boolean> getGenerateSbom();

    /** SBOM document format passed to Syft ({@code -o}). Defaults to {@code cyclonedx-json}. */
    public abstract Property<String> getSbomFormat();

    /**
     * Designates one of this image's artifacts as the module's default (unclassified) main artifact,
     * addressable as the bare {@code group:name:version} instead of under its classifier:
     * {@link #DEFAULT_ARTIFACT_ARCHIVE "archive"} (requires {@link #getCreateArchive() createArchive}) or
     * {@link #DEFAULT_ARTIFACT_REFERENCE "reference"}. Unset by default (neither is the default). At most
     * one artifact per project (across the container/helm/artifacts plugins) may be the default; the
     * variant's {@code classifier} attribute is unaffected, so Gradle attribute selection is unchanged.
     * When the {@code java} plugin is applied, the jar stays the primary artifact of
     * {@code components.java} and this designation is honored only for {@code components.genericArtifacts}.
     */
    public abstract Property<String> getDefaultArtifact();

    /**
     * Whether this image participates in the standard lifecycle tasks (its build task, plus its save
     * task when {@link #getCreateArchive() createArchive} is on, become dependencies of {@code assemble}).
     * Unset by default, inheriting the project-wide
     * {@link io.github.nhwalker.container.gradle.ContainerExtension#getLifecycleIntegration()} (which
     * defaults to {@code true}); set explicitly to opt this image in or out regardless of the project
     * default.
     */
    public abstract Property<Boolean> getLifecycleIntegration();

    /** The base-image dependencies declared via {@code from(...)}. Wired into the build task. */
    public abstract ListProperty<BaseImageReference> getBaseImages();

    // ---- base-image declarations ------------------------------------------------

    /**
     * Declares a base image using the default build-arg name {@value #DEFAULT_BASE_ARG}. See
     * {@link #from(String, Object, String)} for the dependency-notation contract.
     */
    public void from(Object dependencyNotation) {
        from(DEFAULT_BASE_ARG, dependencyNotation);
    }

    /**
     * Declares a base image from another project or an external coordinate. See
     * {@link #from(String, Object, String)} for the dependency-notation contract.
     */
    public void from(String buildArgName, Object dependencyNotation) {
        from(buildArgName, dependencyNotation, null);
    }

    /**
     * Declares a base image, selecting a specific image by name out of a producer that publishes
     * several under one coordinate. {@code dependencyNotation} is anything the project
     * {@code dependencies} handler accepts — a {@code project(':x')} dependency, an external
     * {@code group:name:version} coordinate, etc.; the resolved reference is injected into this
     * image's build as {@code --build-arg <buildArgName>=<reference>} and the producer is built
     * first.
     */
    public void from(String buildArgName, Object dependencyNotation, String imageName) {
        String token = Names.capitalize(sanitize(buildArgName));
        NamedDomainObjectProvider<DependencyScopeConfiguration> bucket =
                ArtifactsDependencies.dependencyBucket(project, name + "BaseImageDep" + token);
        project.getDependencies().add(bucket.getName(), dependencyNotation);

        // Select a reference variant: pin imageType=reference (and imageName when choosing
        // one image out of several). No classifier and no ecosystem fence, so the request
        // also resolves cleanly across projects and composite builds.
        Map<String, String> request = imageName == null
                ? Map.of(ContainerAttributes.IMAGE_TYPE_KEY, ContainerAttributes.IMAGE_TYPE_REFERENCE)
                : Map.of(ContainerAttributes.IMAGE_TYPE_KEY, ContainerAttributes.IMAGE_TYPE_REFERENCE,
                        ContainerAttributes.IMAGE_NAME_KEY, imageName);
        NamedDomainObjectProvider<ResolvableConfiguration> resolvable =
                ArtifactsDependencies.resolvable(project, name + "BaseImageRefs" + token, bucket, null, request);

        BaseImageReference ref = objects.newInstance(BaseImageReference.class);
        ref.getArgName().set(buildArgName);
        ref.getReferenceFiles().from(resolvable);
        getBaseImages().add(ref);
    }

    /** Declares a base image that is a sibling image in the same project ({@code FROM} it). */
    public void from(String buildArgName, ContainerImage sibling) {
        BaseImageReference ref = objects.newInstance(BaseImageReference.class);
        ref.getArgName().set(buildArgName);
        ref.getReferenceFiles().from(
                project.getLayout().getBuildDirectory().file(referenceFilePath(sibling.getName())));
        ref.getReferenceFiles().builtBy(referenceTaskName(sibling.getName()));
        getBaseImages().add(ref);
    }

    /**
     * Alias for {@link #from(String, Object)}, for builds where {@code from} reads awkwardly. See
     * {@link #from(String, Object, String)} for the dependency-notation contract.
     */
    public void baseImage(String buildArgName, Object dependencyNotation) {
        from(buildArgName, dependencyNotation);
    }

    // ---- java reference opt-in --------------------------------------------------

    /**
     * Exposes this image's resolved reference on the {@code main} source set's generated
     * {@code <ProjectName>Images} interface. The interface is generated when at least one image opts
     * in and the {@code java} plugin is applied. See {@link #javaReference(String)}.
     */
    public void javaReference() {
        javaReference(SourceSet.MAIN_SOURCE_SET_NAME);
    }

    /**
     * Exposes this image's resolved reference (its {@code name:tag}, digest-pinned when
     * {@code includeDigest} is on) as a constant on the named source set's generated
     * {@code <ProjectName>Images[<SourceSet>]} interface — the {@code main} set's interface is
     * unsuffixed, others append the capitalized source-set name. The constant value is read from the
     * image's reference file, so the interface's source set compiles only after the image is built and
     * inspected; this scopes the container-engine dependency to that source set (e.g.
     * {@code javaReference('test')} keeps {@code compileJava}/{@code jar} independent of the engine).
     * Opt-in; the interface is generated when at least one image opts in and the {@code java} plugin
     * is applied.
     */
    public void javaReference(String sourceSetName) {
        javaReferenceSourceSets.add(sourceSetName);
    }

    /**
     * The source sets this image's reference constant is exposed on (empty unless
     * {@link #javaReference()} was called). Read by the plugin to build the per-source-set interface.
     */
    public Set<String> getJavaReferenceSourceSets() {
        return javaReferenceSourceSets;
    }

    // ---- naming helpers (shared with the plugin reaction) -----------------------

    public static String buildTaskName(String image) {
        return "build" + Names.capitalize(image) + "Image";
    }

    public static String referenceTaskName(String image) {
        return "write" + Names.capitalize(image) + "ImageReference";
    }

    public static String saveTaskName(String image) {
        return "save" + Names.capitalize(image) + "Image";
    }

    public static String sbomTaskName(String image) {
        return "generate" + Names.capitalize(image) + "ImageSbom";
    }

    public static String referenceElementsName(String image) {
        return image + "ReferenceElements";
    }

    public static String archiveElementsName(String image) {
        return image + "ArchiveElements";
    }

    public static String sbomElementsName(String image) {
        return image + "SbomElements";
    }

    /** The build-relative path of an image's reference file. */
    public static String referenceFilePath(String image) {
        return "container/" + image + "/image-ref.txt";
    }

    /** The build-relative path of an image's exported archive. */
    public static String archiveFilePath(String image, String archiveFormat) {
        return "container/" + image + "/" + image + "."
                + ContainerAttributes.archiveFileExtension(archiveFormat);
    }

    /** The build-relative path of an image's generated SBOM document. */
    public static String sbomFilePath(String image) {
        return "container/" + image + "/" + image + "-sbom.cyclonedx.json";
    }

    private static String sanitize(String token) {
        return token.replaceAll("[^A-Za-z0-9]", "");
    }
}
