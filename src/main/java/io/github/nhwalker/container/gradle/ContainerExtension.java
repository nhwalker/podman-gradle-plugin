package io.github.nhwalker.container.gradle;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

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

    @Inject
    public ContainerExtension(ObjectFactory objects, Project project) {
        // ContainerImage needs the Project to create its dependency configurations, so
        // the container uses a custom element factory rather than Gradle's default.
        this.images = objects.domainObjectContainer(ContainerImage.class,
                name -> objects.newInstance(ContainerImage.class, name, project));
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
     * When {@code true} and a Java plugin is applied, the plugin generates a
     * {@code <ProjectName>Images} Java interface exposing each declared image's full
     * docker identifier (its primary tag) as a {@code public static final String}
     * constant named after the image in UPPER_SNAKE_CASE. The interface is added to
     * the {@code main} source set and (re)generated whenever an image is built.
     * Defaults to {@code false}.
     */
    public abstract Property<Boolean> getGenerateJavaRefs();

    /**
     * The package the generated {@code <ProjectName>Images} interface is placed into.
     * Defaults to the project group; when blank the default package is used.
     */
    public abstract Property<String> getJavaRefsPackage();

    /** The images declared for this project. */
    public NamedDomainObjectContainer<ContainerImage> getImages() {
        return images;
    }

    /** Configures the {@link #getImages() images} container. */
    public void images(Action<? super NamedDomainObjectContainer<ContainerImage>> action) {
        action.execute(images);
    }
}
