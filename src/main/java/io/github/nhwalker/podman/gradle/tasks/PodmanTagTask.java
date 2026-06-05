package io.github.nhwalker.podman.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

/**
 * Adds one or more additional names to an image with {@code podman tag}.
 *
 * <p>{@code podman tag} only accepts a single target per invocation, so this
 * task runs once per entry in {@link #getTargetImages()}.
 *
 * <pre>
 * tasks.register('tagImage', PodmanTagTask) {
 *     sourceImage = 'example/app:latest'
 *     targetImages = ['registry.example.com/example/app:1.0']
 * }
 * </pre>
 */
public abstract class PodmanTagTask extends AbstractPodmanTask {

    /** The existing image name or ID. Required. */
    @Input
    public abstract Property<String> getSourceImage();

    /** The new names to apply. At least one is required. */
    @Input
    public abstract ListProperty<String> getTargetImages();

    @Override
    protected List<String> buildSubcommand() {
        // Only used for dry-run rendering; the action overrides execution to run
        // once per target. Render the first pairing for readability.
        List<String> targets = getTargetImages().get();
        List<String> args = new ArrayList<>();
        args.add("tag");
        args.add(getSourceImage().get());
        if (!targets.isEmpty()) {
            args.add(targets.get(0));
        }
        return args;
    }

    @Override
    public void execute() {
        List<String> targets = getTargetImages().get();
        if (targets.isEmpty()) {
            throw new org.gradle.api.InvalidUserDataException(
                    "PodmanTagTask requires at least one target image");
        }
        // Delegate to the base implementation for each target by temporarily
        // narrowing the target list. Each call assembles its own command.
        for (String target : targets) {
            runSingleTag(target);
        }
    }

    private void runSingleTag(String target) {
        List<String> command = new ArrayList<>();
        command.add(getExecutable().get());
        command.addAll(getGlobalOptions().get());
        if (getConnection().isPresent()) {
            command.add("--connection");
            command.add(getConnection().get());
        }
        command.add("tag");
        command.add(getSourceImage().get());
        command.add(target);

        String rendered = String.join(" ", command);
        if (getDryRun().get()) {
            getLogger().lifecycle("[dry-run] {}", rendered);
            return;
        }
        getLogger().info("Executing: {}", rendered);
        var result = getExecOperations().exec(spec -> {
            spec.commandLine(command);
            spec.setIgnoreExitValue(getIgnoreExitValue().get());
        });
        if (!getIgnoreExitValue().get()) {
            result.assertNormalExitValue();
        }
    }
}
