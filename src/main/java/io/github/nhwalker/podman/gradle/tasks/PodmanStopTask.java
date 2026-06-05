package io.github.nhwalker.podman.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * Stops one or more running containers with {@code podman stop}.
 *
 * <pre>
 * tasks.register('stopApp', PodmanStopTask) {
 *     containers = ['app']
 *     ignoreExitValue = true // tolerate "no such container"
 * }
 * </pre>
 */
public abstract class PodmanStopTask extends AbstractPodmanTask {

    /** Names or IDs of the containers to stop. At least one is required. */
    @Input
    public abstract ListProperty<String> getContainers();

    /** Stop all running containers ({@code --all}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getAll();

    /** Seconds to wait before killing ({@code --time}). */
    @Input
    @Optional
    public abstract Property<Integer> getStopTimeout();

    @SuppressWarnings("this-escape")
    public PodmanStopTask() {
        getAll().convention(false);
    }

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("stop");
        addFlag(args, "--all", getAll().get());
        if (getStopTimeout().isPresent()) {
            addOption(args, "--time", String.valueOf(getStopTimeout().get()));
        }
        args.addAll(getContainers().get());
        return args;
    }
}
