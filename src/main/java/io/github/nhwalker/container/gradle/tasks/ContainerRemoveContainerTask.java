package io.github.nhwalker.container.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

/**
 * Removes one or more containers with {@code podman rm}.
 *
 * <pre>
 * tasks.register('rmApp', ContainerRemoveContainerTask) {
 *     containers = ['app']
 *     force = true
 *     ignoreExitValue = true
 * }
 * </pre>
 */
public abstract class ContainerRemoveContainerTask extends AbstractContainerTask {

    /** Container names or IDs to remove. At least one is required unless {@link #getAll()}. */
    @Input
    public abstract ListProperty<String> getContainers();

    /** Force removal of a running container ({@code --force}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getForce();

    /** Also remove anonymous volumes ({@code --volumes}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getVolumes();

    /** Remove all containers ({@code --all}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getAll();

    @SuppressWarnings("this-escape")
    public ContainerRemoveContainerTask() {
        getForce().convention(false);
        getVolumes().convention(false);
        getAll().convention(false);
    }

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("rm");
        addFlag(args, "--force", getForce().get());
        addFlag(args, "--volumes", getVolumes().get());
        addFlag(args, "--all", getAll().get());
        args.addAll(getContainers().get());
        return args;
    }
}
