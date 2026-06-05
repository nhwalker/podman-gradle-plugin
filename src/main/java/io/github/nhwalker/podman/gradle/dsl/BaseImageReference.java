package io.github.nhwalker.podman.gradle.dsl;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * A single base-image dependency of a build, used as a {@code @Nested} input on
 * {@link io.github.nhwalker.podman.gradle.tasks.PodmanBuildTask}.
 *
 * <p>It pairs the {@code --build-arg} name to inject with the resolved reference
 * file(s) of the base image. Declaring {@link #getReferenceFiles()} as
 * {@code @InputFiles} (a) makes the underlying resolvable configuration resolve
 * lazily/cache-safely and (b) carries the producer task dependency, so the base
 * image is built first. The file content is read at execution time.
 */
public interface BaseImageReference {

    /** The {@code --build-arg} name the resolved reference is injected as. */
    @Input
    Property<String> getArgName();

    /** The resolved base-image reference file(s) (normally exactly one). */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    ConfigurableFileCollection getReferenceFiles();
}
