package io.github.nhwalker.podman.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

import io.github.nhwalker.podman.gradle.tasks.AbstractPodmanTask;

/**
 * Registers the {@code podman} extension and applies its configuration as
 * conventions to every podman task in the project.
 *
 * <p>Apply with:
 * <pre>
 * plugins {
 *     id 'io.github.nhwalker.podman'
 * }
 * </pre>
 *
 * <p>The plugin deliberately does not register any concrete tasks; users add the
 * {@code Podman*Task} types they need. Every such task automatically inherits the
 * executable, global options and connection configured on the extension.
 */
public class PodmanPlugin implements Plugin<Project> {

    /** The name of the project extension contributed by this plugin. */
    public static final String EXTENSION_NAME = "podman";

    /** The task group applied to every podman task. */
    public static final String TASK_GROUP = "podman";

    @Override
    public void apply(Project project) {
        PodmanExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, PodmanExtension.class);
        extension.getExecutable().convention("podman");

        project.getTasks().withType(AbstractPodmanTask.class).configureEach(task -> {
            task.setGroup(TASK_GROUP);
            task.getExecutable().convention(extension.getExecutable());
            task.getGlobalOptions().convention(extension.getGlobalOptions());
            task.getConnection().convention(extension.getConnection());
        });
    }
}
