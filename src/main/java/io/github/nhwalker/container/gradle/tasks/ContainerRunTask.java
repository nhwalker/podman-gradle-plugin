package io.github.nhwalker.container.gradle.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * Runs a container with {@code podman run}.
 *
 * <pre>
 * tasks.register('runApp', ContainerRunTask) {
 *     image = 'example/app:latest'
 *     containerName = 'app'
 *     detach = true
 *     remove = true
 *     ports = ['8080:8080']
 *     environment = ['PROFILE': 'dev']
 *     command = ['--spring.profiles.active=dev']
 * }
 * </pre>
 */
public abstract class ContainerRunTask extends AbstractContainerTask {

    /** The image to run. Required. */
    @Input
    public abstract Property<String> getImage();

    /** Optional container name ({@code --name}). */
    @Input
    @Optional
    public abstract Property<String> getContainerName();

    /** Run detached in the background ({@code -d}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getDetach();

    /** Remove the container when it exits ({@code --rm}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getRemove();

    /** Allocate a pseudo-TTY ({@code -t}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getTty();

    /** Keep STDIN open ({@code -i}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getInteractive();

    /** Port mappings ({@code -p}), e.g. {@code "8080:80"}. */
    @Input
    public abstract ListProperty<String> getPorts();

    /** Volume mounts ({@code -v}), e.g. {@code "/host:/container:ro"}. */
    @Input
    public abstract ListProperty<String> getVolumes();

    /** Environment variables ({@code -e KEY=VALUE}). */
    @Input
    public abstract MapProperty<String, String> getEnvironment();

    /** Additional raw arguments inserted before the image reference. */
    @Input
    public abstract ListProperty<String> getExtraArguments();

    /** Command and arguments passed to the container after the image reference. */
    @Input
    public abstract ListProperty<String> getCommand();

    @SuppressWarnings("this-escape")
    public ContainerRunTask() {
        getDetach().convention(false);
        getRemove().convention(false);
        getTty().convention(false);
        getInteractive().convention(false);
    }

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("run");
        addFlag(args, "-d", getDetach().get());
        addFlag(args, "--rm", getRemove().get());
        addFlag(args, "-t", getTty().get());
        addFlag(args, "-i", getInteractive().get());
        addOption(args, "--name", getContainerName().getOrNull());
        addRepeated(args, "-p", getPorts().get());
        addRepeated(args, "-v", getVolumes().get());
        for (Map.Entry<String, String> e : getEnvironment().get().entrySet()) {
            addOption(args, "-e", e.getKey() + "=" + e.getValue());
        }
        args.addAll(getExtraArguments().get());
        args.add(getImage().get());
        args.addAll(getCommand().get());
        return args;
    }
}
