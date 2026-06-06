package io.github.nhwalker.container.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * Pushes an image to a registry with {@code podman push}.
 *
 * <pre>
 * tasks.register('pushImage', ContainerPushTask) {
 *     image = 'example/app:latest'
 *     destination = 'registry.example.com/example/app:latest'
 * }
 * </pre>
 */
public abstract class ContainerPushTask extends AbstractContainerTask {

    /** The local image name (or ID) to push. Required. */
    @Input
    public abstract Property<String> getImage();

    /** Optional explicit destination reference. */
    @Input
    @Optional
    public abstract Property<String> getDestination();

    /** Controls registry TLS verification ({@code --tls-verify}). Unset = podman default. */
    @Input
    @Optional
    public abstract Property<Boolean> getTlsVerify();

    /** Additional raw arguments appended to the push command. */
    @Input
    public abstract ListProperty<String> getExtraArguments();

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("push");
        if (getTlsVerify().isPresent()) {
            args.add("--tls-verify=" + getTlsVerify().get());
        }
        args.addAll(getExtraArguments().get());
        args.add(getImage().get());
        if (getDestination().isPresent()) {
            args.add(getDestination().get());
        }
        return args;
    }
}
