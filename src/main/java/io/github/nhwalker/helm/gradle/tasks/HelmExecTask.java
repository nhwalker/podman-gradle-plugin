package io.github.nhwalker.helm.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;

/**
 * Runs an arbitrary helm subcommand. Use this as an escape hatch for helm
 * features that do not have a dedicated task.
 *
 * <pre>
 * tasks.register('helmVersion', HelmExecTask) {
 *     arguments = ['version', '--short']
 * }
 * </pre>
 */
public abstract class HelmExecTask extends AbstractHelmTask {

    /** The subcommand and its arguments, e.g. {@code ["version", "--short"]}. */
    @Input
    public abstract ListProperty<String> getArguments();

    @Override
    protected List<String> buildSubcommand() {
        return new ArrayList<>(getArguments().get());
    }
}
