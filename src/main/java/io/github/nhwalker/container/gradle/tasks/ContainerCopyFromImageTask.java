package io.github.nhwalker.container.gradle.tasks;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * Copies one or more files (or directories) out of an image onto the host with
 * {@code podman cp}.
 *
 * <p>{@code podman cp} operates on <em>containers</em>, not images, so to extract
 * files from an image this task:
 * <ol>
 *   <li>creates a container from {@link #getImage()} with {@code podman create}
 *       (the container is created but never started),</li>
 *   <li>runs {@code podman cp <container>:&lt;source&gt; &lt;destination&gt;} for
 *       every entry in {@link #getPaths()}, and</li>
 *   <li>removes the temporary container with {@code podman rm -f}, even if a copy
 *       fails.</li>
 * </ol>
 *
 * <p>If you already have a container to copy from, set {@link #getContainer()}
 * instead of {@link #getImage()} and no container is created or removed.
 *
 * <pre>
 * tasks.register('extractArtifacts', ContainerCopyFromImageTask) {
 *     image = 'example/app:latest'
 *     paths = [
 *         '/app/app.jar'      : layout.buildDirectory.file('extracted/app.jar').get().asFile.path,
 *         '/etc/app/config'   : layout.buildDirectory.dir('extracted/config').get().asFile.path,
 *     ]
 *     copyOptions = ['--archive'] // preserve uid/gid/permissions
 * }
 * </pre>
 */
public abstract class ContainerCopyFromImageTask extends AbstractContainerTask {

    /**
     * The image to extract files from. A temporary container is created from it.
     * Mutually exclusive with {@link #getContainer()}; exactly one is required.
     */
    @Input
    @Optional
    public abstract Property<String> getImage();

    /**
     * An existing container to copy from. When set, no container is created or
     * removed. Mutually exclusive with {@link #getImage()}.
     */
    @Input
    @Optional
    public abstract Property<String> getContainer();

    /**
     * The files to copy, mapping a source path <em>inside the image/container</em>
     * to a destination path on the host. At least one entry is required.
     */
    @Input
    public abstract MapProperty<String, String> getPaths();

    /** Extra arguments for the intermediate {@code podman create} (e.g. {@code --platform}). */
    @Input
    public abstract ListProperty<String> getCreateOptions();

    /** Extra arguments for each {@code podman cp} (e.g. {@code --archive}, {@code --overwrite}). */
    @Input
    public abstract ListProperty<String> getCopyOptions();

    /**
     * Whether to remove the temporary container afterwards. Only applies when a
     * container was created from {@link #getImage()}. Defaults to {@code true}.
     */
    @Input
    public abstract Property<Boolean> getRemoveContainer();

    @SuppressWarnings("this-escape")
    public ContainerCopyFromImageTask() {
        getRemoveContainer().convention(true);
    }

    /**
     * Used only for {@code assembleCommand()} / dry-run rendering of the primary
     * {@code cp} step; real execution is orchestrated in {@link #execute()}.
     */
    @Override
    protected List<String> buildSubcommand() {
        String ref = getContainer().getOrElse("<container>");
        Map<String, String> paths = getPaths().get();
        if (paths.isEmpty()) {
            List<String> args = new ArrayList<>();
            args.add("cp");
            args.addAll(getCopyOptions().get());
            return args;
        }
        Map.Entry<String, String> first = paths.entrySet().iterator().next();
        return cpCommand(ref, first.getKey(), first.getValue());
    }

    @Override
    public void execute() {
        Map<String, String> paths = getPaths().get();
        if (paths.isEmpty()) {
            throw new InvalidUserDataException("ContainerCopyFromImageTask requires at least one path to copy");
        }
        boolean hasImage = getImage().isPresent();
        boolean hasContainer = getContainer().isPresent();
        if (hasImage == hasContainer) {
            throw new InvalidUserDataException(
                    "ContainerCopyFromImageTask requires exactly one of 'image' or 'container' to be set");
        }

        if (getDryRun().get()) {
            logPlan(paths);
            return;
        }

        boolean createdHere = hasImage;
        String containerRef = hasContainer
                ? getContainer().get()
                : createContainer();
        try {
            for (Map.Entry<String, String> entry : paths.entrySet()) {
                copyOne(containerRef, entry.getKey(), entry.getValue());
            }
        } finally {
            if (createdHere && getRemoveContainer().get()) {
                removeContainerQuietly(containerRef);
            }
        }
    }

    // ---- the three engine commands, shared by real execution and the dry-run plan ----

    /** The {@code create} subcommand building the temporary container from {@link #getImage()}. */
    private List<String> createCommand() {
        List<String> create = new ArrayList<>();
        create.add("create");
        create.addAll(getCreateOptions().get());
        create.add(getImage().get());
        return create;
    }

    /** The {@code cp} subcommand copying one source path out of {@code containerRef}. */
    private List<String> cpCommand(String containerRef, String source, String destination) {
        List<String> cp = new ArrayList<>();
        cp.add("cp");
        cp.addAll(getCopyOptions().get());
        cp.add(containerRef + ":" + source);
        cp.add(destination);
        return cp;
    }

    /** The {@code rm} subcommand removing the temporary container. */
    private static List<String> removeCommand(String containerRef) {
        return List.of("rm", "-f", containerRef);
    }

    /** Creates a stopped container from the image and returns its id. */
    private String createContainer() {
        // `podman create` prints the new container id on stdout.
        String output = runSubcommand(createCommand(), /* captureStdout */ true);
        String id = lastNonBlankLine(output);
        if (id == null) {
            throw new InvalidUserDataException(
                    "Could not determine the container id created from image '" + getImage().get() + "'");
        }
        getLogger().info("Created temporary container {} from image {}", id, getImage().get());
        return id;
    }

    private void copyOne(String containerRef, String source, String destination) {
        ensureParentDirectory(destination);
        runSubcommand(cpCommand(containerRef, source, destination), false);
    }

    /** Removes the temporary container, never failing the build on cleanup errors. */
    private void removeContainerQuietly(String containerRef) {
        List<String> command = assembleCommandFor(removeCommand(containerRef));
        getLogger().info("Removing temporary container {}", containerRef);
        getExecOperations().exec(spec -> {
            spec.commandLine(command);
            spec.setIgnoreExitValue(true);
        });
    }

    private void logPlan(Map<String, String> paths) {
        String ref = getContainer().getOrElse("<container-from " + getImage().getOrElse("?") + ">");
        if (getImage().isPresent()) {
            getLogger().lifecycle("[dry-run] {}", String.join(" ", assembleCommandFor(createCommand())));
        }
        for (Map.Entry<String, String> entry : paths.entrySet()) {
            getLogger().lifecycle("[dry-run] {}",
                    String.join(" ", assembleCommandFor(cpCommand(ref, entry.getKey(), entry.getValue()))));
        }
        if (getImage().isPresent() && getRemoveContainer().get()) {
            getLogger().lifecycle("[dry-run] {}",
                    String.join(" ", assembleCommandFor(removeCommand(ref))));
        }
    }

    private static void ensureParentDirectory(String destination) {
        File parent = new File(destination).getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
    }

    private static String lastNonBlankLine(String output) {
        if (output == null) {
            return null;
        }
        String result = null;
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                result = line.trim();
            }
        }
        return result;
    }
}
