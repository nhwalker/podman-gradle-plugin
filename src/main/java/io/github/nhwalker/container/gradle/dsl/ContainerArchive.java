package io.github.nhwalker.container.gradle.dsl;

import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies;
import io.github.nhwalker.artifacts.gradle.support.Names;
import io.github.nhwalker.container.gradle.dependency.ContainerAttributes;
import io.github.nhwalker.container.gradle.tasks.ContainerArchiveTask;

/**
 * A multi-image archive declared in the {@code container { archives { } }} container.
 *
 * <p>Where {@code images { app { createArchive = true } }} exports one tar per image, an archive
 * bundles <em>several</em> images into a single tar via one {@code podman save img1 img2 …} — the
 * "offline bundle you {@code podman load} in one shot" shape. The plugin registers a save task for it
 * and publishes the resulting tar as an {@code archive} variant of the project's software component,
 * selectable exactly like a single-image archive (by classifier, or by the
 * {@link ContainerAttributes#IMAGE_NAME_KEY imageName}/{@link ContainerAttributes#IMAGE_TYPE_KEY
 * imageType}/{@link ContainerAttributes#ARCHIVE_FORMAT_KEY archiveFormat} attributes).
 *
 * <p>Members are declared in any combination, and are saved in declaration order:
 * <pre>
 * container {
 *     images { base { tags = ['example/base:1.0'] } }
 *     archives {
 *         bundle {
 *             image images.base                          // a sibling image in this project
 *             from project(':worker-service')            // a cross-project image reference
 *             from 'com.example:platform:1.0', 'runtime' // an external coordinate (one image of several)
 *             referenceFile file('refs/legacy-ref.txt')  // a published name:tag@digest reference file
 *             image 'docker.io/library/alpine:3.20'      // an arbitrary literal image
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>Reference-backed members ({@link #image(ContainerImage)}, {@link #from}, {@link #referenceFile})
 * carry the producing image's digest, so the archive re-saves when their content changes; literal
 * members ({@link #image(String)}) are pinned only by their tag string. Before saving, the task runs
 * {@code podman pull --policy <pullPolicy>} over the members so anything missing from local storage is
 * fetched first.
 */
public abstract class ContainerArchive implements Named {

    private final String name;
    private final Project project;
    // Distinct, stable per-member configuration-name suffix (multiple from(...) members are allowed).
    private int memberCounter;

    @Inject
    @SuppressWarnings("this-escape")
    public ContainerArchive(String name, Project project) {
        this.name = name;
        this.project = project;
        getFormat().convention(ContainerAttributes.ARCHIVE_FORMAT_OCI);
        getClassifier().convention(name);
        getDefaultArtifact().convention(false);
        getPullPolicy().convention(ContainerArchiveTask.POLICY_MISSING);
    }

    @Override
    public String getName() {
        return name;
    }

    /** Archive container format ({@code podman save --format}). Defaults to {@code oci-archive}. */
    public abstract Property<String> getFormat();

    /** The classifier selecting this archive within the module. Defaults to the archive name. */
    public abstract Property<String> getClassifier();

    /**
     * Whether to also publish this archive as the module's default (unclassified) main artifact,
     * addressable as the bare {@code group:name:version}. Defaults to {@code false}. At most one
     * artifact per project (across the container/helm/artifacts plugins) may be the default; the
     * variant's {@code classifier} attribute is unaffected, so Gradle attribute selection is unchanged.
     * When the {@code java} plugin is applied, the jar stays the primary artifact of
     * {@code components.java} and this designation is honored only for {@code components.genericArtifacts}.
     */
    public abstract Property<Boolean> getDefaultArtifact();

    /**
     * The pull policy applied to the members before the save runs. Defaults to {@code missing} (pull only
     * members not already in local storage, via {@code podman image exists}). {@code always} pulls every
     * member — which fails for local-only tags such as sibling images — and {@code never} pulls nothing
     * (the save fails if a member is absent locally). Implemented with {@code podman image exists}/
     * {@code podman pull} rather than {@code pull --policy}, which is not available on older podman.
     */
    public abstract Property<String> getPullPolicy();

    /**
     * Whether this archive participates in the standard lifecycle tasks (its save task becomes a
     * dependency of {@code assemble}). Unset by default, inheriting the project-wide
     * {@link io.github.nhwalker.container.gradle.ContainerExtension#getLifecycleIntegration()} (which
     * defaults to {@code true}); set explicitly to opt this archive in or out regardless of the project
     * default.
     */
    public abstract Property<Boolean> getLifecycleIntegration();

    /**
     * The resolved reference files of reference-backed members (siblings, cross-project/external
     * references, and published reference files). Read at execution time for the {@code name:tag} to
     * save, and tracked as content-identity inputs. Wired into the save task.
     */
    public abstract ConfigurableFileCollection getImageReferenceFiles();

    /** Literal {@code name:tag} members declared via {@link #image(String)}. Wired into the save task. */
    public abstract ListProperty<String> getImageStrings();

    // ---- member declarations ----------------------------------------------------

    /** Bundles a sibling image declared in this same project's {@code images { }} container. */
    public void image(ContainerImage sibling) {
        getImageReferenceFiles().from(
                project.getLayout().getBuildDirectory().file(
                        ContainerImage.referenceFilePath(sibling.getName())));
        getImageReferenceFiles().builtBy(ContainerImage.referenceTaskName(sibling.getName()));
    }

    /** Bundles several sibling images. See {@link #image(ContainerImage)}. */
    public void images(ContainerImage... siblings) {
        for (ContainerImage sibling : siblings) {
            image(sibling);
        }
    }

    /** Bundles an arbitrary literal image ({@code name:tag} or id) already resolvable to local storage. */
    public void image(String imageReference) {
        getImageStrings().add(imageReference);
    }

    /** Bundles an image referenced by another project or an external coordinate. */
    public void from(Object dependencyNotation) {
        from(dependencyNotation, null);
    }

    /**
     * Bundles an image referenced by another project or an external coordinate, selecting a specific
     * image by name out of a producer that publishes several under one coordinate.
     */
    public void from(Object dependencyNotation, String imageName) {
        String token = "Member" + nextMemberToken();
        NamedDomainObjectProvider<DependencyScopeConfiguration> bucket =
                ArtifactsDependencies.dependencyBucket(project, name + "ArchiveDep" + token);
        project.getDependencies().add(bucket.getName(), dependencyNotation);

        // Select a reference variant: pin imageType=reference (and imageName when choosing one image
        // out of several). No classifier and no ecosystem fence, so the request also resolves cleanly
        // across projects and composite builds.
        Map<String, String> request = imageName == null
                ? Map.of(ContainerAttributes.IMAGE_TYPE_KEY, ContainerAttributes.IMAGE_TYPE_REFERENCE)
                : Map.of(ContainerAttributes.IMAGE_TYPE_KEY, ContainerAttributes.IMAGE_TYPE_REFERENCE,
                        ContainerAttributes.IMAGE_NAME_KEY, imageName);
        NamedDomainObjectProvider<ResolvableConfiguration> resolvable =
                ArtifactsDependencies.resolvable(project, name + "ArchiveRefs" + token, bucket, null, request);
        getImageReferenceFiles().from(resolvable);
    }

    /** Bundles an image from a published {@code name:tag@digest} reference file (any file notation). */
    public void referenceFile(Object fileNotation) {
        getImageReferenceFiles().from(fileNotation);
    }

    private int nextMemberToken() {
        return memberCounter++;
    }

    // ---- naming helpers (shared with the plugin reaction) -----------------------

    public static String saveTaskName(String archive) {
        return "save" + Names.capitalize(archive) + "Archive";
    }

    public static String archiveBundleElementsName(String archive) {
        return archive + "ArchiveBundleElements";
    }

    /** The build-relative path of a multi-image archive's exported tar. */
    public static String archiveFilePath(String archive, String archiveFormat) {
        return "container/archives/" + archive + "/" + archive + "."
                + ContainerAttributes.archiveFileExtension(archiveFormat);
    }
}
