package io.github.nhwalker.container.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;

/**
 * Runs an arbitrary container engine subcommand. Use this as an escape hatch for
 * engine features that do not have a dedicated task.
 *
 * <pre>
 * tasks.register('containerImages', ContainerExecTask) {
 *     arguments = ['images', '--format', '{{.Repository}}:{{.Tag}}']
 * }
 * </pre>
 */
public abstract class ContainerExecTask extends AbstractContainerTask {

    /** The subcommand and its arguments, e.g. {@code ["images", "-a"]}. */
    @Input
    public abstract ListProperty<String> getArguments();

    @Override
    protected List<String> buildSubcommand() {
        return new ArrayList<>(getArguments().get());
    }
}
