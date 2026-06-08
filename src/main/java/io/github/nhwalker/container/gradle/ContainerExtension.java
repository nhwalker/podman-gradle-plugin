package io.github.nhwalker.container.gradle;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import io.github.nhwalker.container.gradle.dsl.ContainerArchive;
import io.github.nhwalker.container.gradle.dsl.ContainerImage;

/**
 * Project-level configuration for the Container plugin.
 *
 * <p>The {@code executable}/{@code globalOptions}/{@code connection} values are
 * used as conventions for every container task in the project. The {@code images}
 * container declares images to build and share as dependencies:
 *
 * <pre>
 * container {
 *     executable = '/usr/local/bin/podman'
 *     images {
 *         base { tags = ['example/base:1.0'] }
 *         app  { tags = ['example/app:1.0']; from 'BASE_IMAGE', images.base }
 *     }
 * }
 * </pre>
 */
public abstract class ContainerExtension {

    private final NamedDomainObjectContainer<ContainerImage> images;
    private final NamedDomainObjectContainer<ContainerArchive> archives;

    @Inject
    public ContainerExtension(ObjectFactory objects, Project project) {
        // ContainerImage/ContainerArchive need the Project to create their dependency configurations,
        // so the containers use a custom element factory rather than Gradle's default.
        this.images = objects.domainObjectContainer(ContainerImage.class,
                name -> objects.newInstance(ContainerImage.class, name, project));
        this.archives = objects.domainObjectContainer(ContainerArchive.class,
                name -> objects.newInstance(ContainerArchive.class, name, project));
    }

    /**
     * The container engine executable to invoke. Defaults to {@code "podman"}, resolved
     * against the {@code PATH} of the Gradle process.
     */
    public abstract Property<String> getExecutable();

    /**
     * Global options inserted immediately after the executable and before the
     * subcommand on every invocation (for example {@code ["--log-level",
     * "debug"]} or {@code ["--url", "ssh://host"]}). Empty by default.
     */
    public abstract ListProperty<String> getGlobalOptions();

    /**
     * Optional named connection passed as {@code --connection <name>} to reach a
     * remote podman service. Unset by default.
     */
    public abstract Property<String> getConnection();

    /**
     * The Syft container image used to generate SBOMs (for images that opt in via
     * {@link io.github.nhwalker.container.gradle.dsl.ContainerImage#getGenerateSbom() generateSbom}).
     * Run with {@code podman run}; pin a specific tag for reproducible scans. Defaults to a pinned
     * {@code docker.io/anchore/syft} release.
     */
    public abstract Property<String> getSyftImage();

    /**
     * The {@code podman run --pull} policy used when running the {@link #getSyftImage() Syft image}
     * ({@code missing}, {@code always}, {@code never} or {@code newer}). This governs how podman pulls
     * the Syft image itself, not Syft's own behavior. Defaults to {@code missing}.
     */
    public abstract Property<String> getSyftPullPolicy();

    /**
     * The package the generated {@code <ProjectName>Images} interface(s) are placed into.
     * Defaults to the project group; when blank the default package is used.
     *
     * <p>The interface is generated, per source set, whenever an image opts in via
     * {@link io.github.nhwalker.container.gradle.dsl.ContainerImage#javaReference() javaReference(...)}
     * and the {@code java} plugin is applied. Each opted-in image contributes a
     * {@code public static final String} constant (named after the image in UPPER_SNAKE_CASE) whose
     * value is read from the image's reference file (its {@code name:tag}, digest-pinned when that
     * image's {@code includeDigest} is on), so generating the interface builds and inspects the
     * opted-in images.
     */
    public abstract Property<String> getReferencesPackage();

    /**
     * The name of the generated interface for the {@code main} source set. Defaults to
     * {@code <ProjectName>Images}; override to customize (e.g. {@code 'MyImages'}). Named
     * {@code referencesClassName} consistently with the helm and generic-artifacts plugins. Images
     * exposed to a non-{@code main} source set append the capitalized source-set name (e.g.
     * {@code MyImagesTest}).
     */
    public abstract Property<String> getReferencesClassName();

    /**
     * Whether declared images participate in the standard lifecycle tasks by default: when {@code true}
     * (the default) building {@code assemble} (and therefore {@code build}) builds every image — and
     * saves its archive when {@link io.github.nhwalker.container.gradle.dsl.ContainerImage#getCreateArchive()
     * createArchive} is on. Set to {@code false} to opt the whole project out; individual images can
     * override either way via
     * {@link io.github.nhwalker.container.gradle.dsl.ContainerImage#getLifecycleIntegration()}.
     */
    public abstract Property<Boolean> getLifecycleIntegration();

    /** The images declared for this project. */
    public NamedDomainObjectContainer<ContainerImage> getImages() {
        return images;
    }

    /** Configures the {@link #getImages() images} container. */
    public void images(Action<? super NamedDomainObjectContainer<ContainerImage>> action) {
        action.execute(images);
    }

    /** The multi-image archives declared for this project. */
    public NamedDomainObjectContainer<ContainerArchive> getArchives() {
        return archives;
    }

    /** Configures the {@link #getArchives() archives} container. */
    public void archives(Action<? super NamedDomainObjectContainer<ContainerArchive>> action) {
        action.execute(archives);
    }
}
