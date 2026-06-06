package io.github.nhwalker.container.gradle.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;

/**
 * Writes a built image's reference to a file so it can be shared with other
 * projects as a consumable artifact.
 *
 * <p>The file's first line is the image coordinate ({@code name:tag}); when
 * {@link #getIncludeDigest()} is enabled a second line carries the digest-pinned
 * form ({@code name@sha256:…}) obtained from {@code podman image inspect}.
 * Consumers (for example {@link ContainerBuildTask}'s base-image injection) read the
 * first line.
 *
 * <p>This task only writes a small text file (and optionally runs a read-only
 * {@code inspect}); it should depend on the corresponding build task so the image
 * exists in podman storage first.
 */
public abstract class ContainerImageReferenceTask extends AbstractContainerTask {

    /** The image coordinate ({@code name:tag}) to record. Required. */
    @Input
    public abstract Property<String> getImageReference();

    /** Whether to also record the digest-pinned reference. Defaults to {@code true}. */
    @Input
    public abstract Property<Boolean> getIncludeDigest();

    /** The reference file to write. */
    @OutputFile
    public abstract RegularFileProperty getReferenceFile();

    @SuppressWarnings("this-escape")
    public ContainerImageReferenceTask() {
        getIncludeDigest().convention(true);
    }

    @Override
    protected List<String> buildSubcommand() {
        // Only used for assembleCommand()/dry-run rendering of the inspect step.
        return List.of("image", "inspect", "--format", "{{.Digest}}", getImageReference().get());
    }

    @Override
    public void execute() {
        String reference = getImageReference().get();
        StringBuilder content = new StringBuilder(reference).append(System.lineSeparator());

        if (getIncludeDigest().get() && !getDryRun().get()) {
            String digest = inspectDigest(reference);
            if (digest != null && !digest.isBlank()) {
                String name = reference.contains(":") ? reference.substring(0, reference.lastIndexOf(':')) : reference;
                content.append(name).append('@').append(digest.strip()).append(System.lineSeparator());
            }
        }

        Path target = getReferenceFile().get().getAsFile().toPath();
        if (getDryRun().get()) {
            getLogger().lifecycle("[dry-run] write image reference {} -> {}", reference, target);
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write image reference file " + target, e);
        }
    }

    /** Runs {@code podman image inspect} to capture the image digest, tolerating failure. */
    private String inspectDigest(String reference) {
        try {
            String output = runSubcommand(
                    List.of("image", "inspect", "--format", "{{.Digest}}", reference), true);
            return output == null ? null : output.strip();
        } catch (RuntimeException e) {
            getLogger().info("Could not inspect digest for {}: {}", reference, e.getMessage());
            return null;
        }
    }
}
