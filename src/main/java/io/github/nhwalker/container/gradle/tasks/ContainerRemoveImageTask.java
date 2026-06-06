package io.github.nhwalker.container.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

/**
 * Removes one or more images with {@code podman rmi}.
 *
 * <pre>
 * tasks.register('removeImage', ContainerRemoveImageTask) {
 *     images = ['example/app:latest']
 *     force = true
 *     ignoreExitValue = true // tolerate "image not known"
 * }
 * </pre>
 */
public abstract class ContainerRemoveImageTask extends AbstractContainerTask {

    /** Image names or IDs to remove. At least one is required unless {@link #getAll()}. */
    @Input
    public abstract ListProperty<String> getImages();

    /** Force removal of the image and any associated containers ({@code --force}). */
    @Input
    public abstract Property<Boolean> getForce();

    /** Remove all images ({@code --all}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getAll();

    @SuppressWarnings("this-escape")
    public ContainerRemoveImageTask() {
        getForce().convention(false);
        getAll().convention(false);
    }

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("rmi");
        addFlag(args, "--force", getForce().get());
        addFlag(args, "--all", getAll().get());
        args.addAll(getImages().get());
        return args;
    }
}
