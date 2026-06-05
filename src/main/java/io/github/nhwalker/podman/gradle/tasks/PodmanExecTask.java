package io.github.nhwalker.podman.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;

/**
 * Runs an arbitrary podman subcommand. Use this as an escape hatch for podman
 * features that do not have a dedicated task.
 *
 * <pre>
 * tasks.register('podmanImages', PodmanExecTask) {
 *     arguments = ['images', '--format', '{{.Repository}}:{{.Tag}}']
 * }
 * </pre>
 */
public abstract class PodmanExecTask extends AbstractPodmanTask {

    /** The subcommand and its arguments, e.g. {@code ["images", "-a"]}. */
    @Input
    public abstract ListProperty<String> getArguments();

    @Override
    protected List<String> buildSubcommand() {
        return new ArrayList<>(getArguments().get());
    }
}
