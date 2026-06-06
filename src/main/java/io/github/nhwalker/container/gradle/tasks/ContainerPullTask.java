package io.github.nhwalker.container.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * Pulls an image from a registry with {@code podman pull}.
 *
 * <pre>
 * tasks.register('pullBase', ContainerPullTask) {
 *     image = 'docker.io/library/eclipse-temurin:21-jre'
 * }
 * </pre>
 */
public abstract class ContainerPullTask extends AbstractContainerTask {

    /** The image reference to pull. Required. */
    @Input
    public abstract Property<String> getImage();

    /** Target platform to pull, e.g. {@code linux/amd64} ({@code --platform}). */
    @Input
    @Optional
    public abstract Property<String> getPlatform();

    /** Controls registry TLS verification ({@code --tls-verify}). Unset = podman default. */
    @Input
    @Optional
    public abstract Property<Boolean> getTlsVerify();

    /** Additional raw arguments appended to the pull command. */
    @Input
    public abstract ListProperty<String> getExtraArguments();

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("pull");
        addOption(args, "--platform", getPlatform().getOrNull());
        if (getTlsVerify().isPresent()) {
            args.add("--tls-verify=" + getTlsVerify().get());
        }
        args.addAll(getExtraArguments().get());
        args.add(getImage().get());
        return args;
    }
}
