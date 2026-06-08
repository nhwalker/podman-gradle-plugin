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
import org.gradle.process.ExecResult;

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

    /** {@link #getPullPolicy() pullPolicy} value: pull only members absent from local storage (default). */
    public static final String POLICY_MISSING = "missing";

    /** {@link #getPullPolicy() pullPolicy} value: pull every member regardless of local storage. */
    public static final String POLICY_ALWAYS = "always";

    /** {@link #getPullPolicy() pullPolicy} value: never pull (the save fails if a member is absent). */
    public static final String POLICY_NEVER = "never";

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

    /**
     * Pull policy applied to the members before the save: {@code missing} (default; pull only members
     * not already in local storage), {@code always} (pull every member), or {@code never} (pull nothing).
     */
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
        String policy = getPullPolicy().getOrElse(POLICY_MISSING);
        if (!POLICY_MISSING.equals(policy) && !POLICY_ALWAYS.equals(policy) && !POLICY_NEVER.equals(policy)) {
            throw new InvalidUserDataException("container archive '" + getName() + "' pullPolicy must be '"
                    + POLICY_MISSING + "', '" + POLICY_ALWAYS + "', or '" + POLICY_NEVER + "', but was '"
                    + policy + "'");
        }
        if (getDryRun().get()) {
            getLogger().lifecycle("[dry-run] ensure present (policy={}): {}", policy, images);
            runSubcommand(buildSubcommand(), false);
            return;
        }
        // `podman pull` has no portable --policy flag (it was added in podman 5), so apply the policy
        // here: `always` pulls every member, `missing` pulls only those `podman image exists` reports
        // absent (locally-built/cross-project members are present and skipped), `never` pulls nothing.
        if (!POLICY_NEVER.equals(policy)) {
            for (String image : images) {
                if (POLICY_ALWAYS.equals(policy) || !imageExists(image)) {
                    runSubcommand(List.of("pull", image), false);
                }
            }
        }
        runSubcommand(buildSubcommand(), false);
    }

    /** Whether {@code podman image exists <ref>} reports the image present (exit 0) in local storage. */
    private boolean imageExists(String image) {
        List<String> command = assembleCommandFor(List.of("image", "exists", image));
        ExecResult result = getExecOperations().exec(spec -> {
            spec.commandLine(command);
            spec.setIgnoreExitValue(true);
        });
        return result.getExitValue() == 0;
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
