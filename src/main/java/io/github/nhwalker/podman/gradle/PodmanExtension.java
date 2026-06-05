package io.github.nhwalker.podman.gradle;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import io.github.nhwalker.podman.gradle.dsl.PodmanImage;

/**
 * Project-level configuration for the Podman plugin.
 *
 * <p>The {@code executable}/{@code globalOptions}/{@code connection} values are
 * used as conventions for every podman task in the project. The {@code images}
 * container declares images to build and share as dependencies:
 *
 * <pre>
 * podman {
 *     executable = '/usr/local/bin/podman'
 *     images {
 *         base { tags = ['example/base:1.0'] }
 *         app  { tags = ['example/app:1.0']; from 'BASE_IMAGE', images.base }
 *     }
 * }
 * </pre>
 */
public abstract class PodmanExtension {

    private final NamedDomainObjectContainer<PodmanImage> images;

    @Inject
    public PodmanExtension(ObjectFactory objects, Project project) {
        // PodmanImage needs the Project to create its dependency configurations, so
        // the container uses a custom element factory rather than Gradle's default.
        this.images = objects.domainObjectContainer(PodmanImage.class,
                name -> objects.newInstance(PodmanImage.class, name, project));
    }

    /**
     * The podman executable to invoke. Defaults to {@code "podman"}, resolved
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

    /** The images declared for this project. */
    public NamedDomainObjectContainer<PodmanImage> getImages() {
        return images;
    }

    /** Configures the {@link #getImages() images} container. */
    public void images(Action<? super NamedDomainObjectContainer<PodmanImage>> action) {
        action.execute(images);
    }
}
