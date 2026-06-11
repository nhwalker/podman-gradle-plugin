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
 * Writes a built image's reference to a single-line file so it can be shared with
 * other projects as a consumable artifact.
 *
 * <p>The line is the image coordinate ({@code name:tag}); when {@link #getIncludeDigest()}
 * is enabled and a digest is available, the digest-pinned suffix is appended in place, giving
 * the canonical {@code name:tag@sha256:…} form (the digest is obtained from
 * {@code podman image inspect}). Consumers (for example {@link ContainerBuildTask}'s base-image
 * injection) read this single line.
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
        // The digest is read from podman's mutable storage at execution time, not
        // from any declared input, so the recorded digest can go stale if the image
        // is rebuilt under the same tag. When a digest is recorded, never report the
        // task up-to-date so the recorded digest always reflects the current image. (The
        // coordinate-only case is a pure function of the inputs, so normal up-to-date
        // checks apply and the file content only changes when the coordinate does.)
        getOutputs().upToDateWhen(t -> !getIncludeDigest().get());
    }

    @Override
    protected List<String> buildSubcommand() {
        // Only used for assembleCommand()/dry-run rendering of the inspect step.
        return inspectCommand(getImageReference().get());
    }

    /** The {@code image inspect} subcommand that prints the image's digest. */
    private static List<String> inspectCommand(String reference) {
        return List.of("image", "inspect", "--format", "{{.Digest}}", reference);
    }

    @Override
    public void execute() {
        String reference = getImageReference().get();
        // Build the single canonical line: name:tag, with @sha256:… appended in place when a
        // digest is available (so the tag and the content-pinned form travel together).
        StringBuilder line = new StringBuilder(reference);
        if (getIncludeDigest().get() && !getDryRun().get()) {
            String digest = inspectDigest(reference);
            if (digest != null && !digest.isBlank()) {
                line.append('@').append(digest.strip());
            }
        }

        Path target = getReferenceFile().get().getAsFile().toPath();
        if (getDryRun().get()) {
            getLogger().lifecycle("[dry-run] write image reference {} -> {}", reference, target);
            return;
        }
        String content = line.append(System.lineSeparator()).toString();
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write image reference file " + target, e);
        }
    }

    /** Runs {@code podman image inspect} to capture the image digest, tolerating failure. */
    private String inspectDigest(String reference) {
        try {
            String output = runSubcommand(inspectCommand(reference), true);
            return output == null ? null : output.strip();
        } catch (RuntimeException e) {
            getLogger().info("Could not inspect digest for {}: {}", reference, e.getMessage());
            return null;
        }
    }
}
