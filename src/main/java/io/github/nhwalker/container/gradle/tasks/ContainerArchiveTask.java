package io.github.nhwalker.container.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * Exports several images into a single tar archive with one {@code podman save img1 img2 …}.
 *
 * <p>The member images come from two sources, combined in declaration order:
 * <ul>
 *   <li>{@link #getImageReferenceFiles() reference files} (siblings, cross-project/external references,
 *       and published reference files) — each is read at execution time and its leading
 *       {@code name:tag} taken, dropping any {@code @sha256:…} digest ({@code podman save} takes a
 *       {@code name:tag}/id, not the digest triple); and</li>
 *   <li>{@link #getImageStrings() literal strings} — arbitrary {@code name:tag} values.</li>
 * </ul>
 *
 * <p>Before saving, a single {@code podman pull --policy <pullPolicy>} over the members fetches any
 * not already in local storage. The reference files are declared as {@code @InputFiles} so the archive
 * re-saves when a reference-backed member's recorded digest changes (the same content-pinning
 * {@link ContainerSaveTask} uses); the pull runs only inside the task action and so never affects the
 * up-to-date check.
 */
public abstract class ContainerArchiveTask extends AbstractContainerTask {

    /** Reference files of the reference-backed members ({@code name:tag} or {@code name:tag@sha256:…}). */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getImageReferenceFiles();

    /** Literal {@code name:tag} members. */
    @Input
    public abstract ListProperty<String> getImageStrings();

    /** Archive format ({@code --format}), e.g. {@code oci-archive} or {@code docker-archive}. */
    @Input
    @Optional
    public abstract Property<String> getFormat();

    /** Pull policy passed to {@code podman pull --policy} before the save. */
    @Input
    public abstract Property<String> getPullPolicy();

    /** The combined archive file to write ({@code -o}). Required. */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("save");
        addOption(args, "--format", getFormat().getOrNull());
        addOption(args, "-o", getOutputFile().get().getAsFile().getAbsolutePath());
        args.addAll(resolveImages());
        return args;
    }

    @Override
    public void execute() {
        List<String> images = resolveImages();
        if (getDryRun().get()) {
            // runSubcommand also logs/skips under dry-run, but rendering both steps keeps the intent clear.
            getLogger().lifecycle("[dry-run] pull --policy {} {}", getPullPolicy().get(), images);
        }
        List<String> pull = new ArrayList<>();
        pull.add("pull");
        pull.add("--policy");
        pull.add(getPullPolicy().get());
        pull.addAll(images);
        runSubcommand(pull, false);
        runSubcommand(buildSubcommand(), false);
    }

    /** The member images to save, reference-file members (digest stripped) first, then literal strings. */
    private List<String> resolveImages() {
        List<String> images = new ArrayList<>();
        for (File file : getImageReferenceFiles().getFiles()) {
            String reference = readReference(file);
            if (reference != null) {
                int at = reference.indexOf('@');
                images.add(at >= 0 ? reference.substring(0, at) : reference);
            }
        }
        images.addAll(getImageStrings().get());
        if (images.isEmpty()) {
            throw new InvalidUserDataException(
                    "container archive '" + getName() + "' must declare at least one image to bundle");
        }
        return images;
    }

    /** Reads the first non-blank line of a reference file (the coordinate, digest-pinned when available). */
    private static String readReference(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read image reference file " + file, e);
        }
    }
}
