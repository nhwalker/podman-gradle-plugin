package io.github.nhwalker.podman.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Project-level configuration for the Podman plugin.
 *
 * <p>Values configured here are used as conventions for every
 * {@link io.github.nhwalker.podman.gradle.tasks.AbstractPodmanTask} registered
 * in the project, so common settings (which executable to run, a remote
 * connection name, global flags) only have to be specified once.
 *
 * <pre>
 * podman {
 *     executable = '/usr/local/bin/podman'
 *     connection = 'my-remote'
 *     globalOptions = ['--log-level', 'info']
 * }
 * </pre>
 */
public abstract class PodmanExtension {

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
}
