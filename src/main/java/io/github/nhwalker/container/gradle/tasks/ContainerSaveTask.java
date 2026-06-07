package io.github.nhwalker.container.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * Exports an image to a tar archive with {@code podman save}.
 *
 * <pre>
 * tasks.register('saveImage', ContainerSaveTask) {
 *     image = 'example/app:latest'
 *     outputFile = layout.buildDirectory.file('images/app.tar')
 * }
 * </pre>
 */
public abstract class ContainerSaveTask extends AbstractContainerTask {

    /** The image to export. Required. */
    @Input
    public abstract Property<String> getImage();

    /** The archive file to write ({@code -o}). Required. */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /** Archive format, e.g. {@code oci-archive} or {@code docker-archive} ({@code --format}). */
    @Input
    @Optional
    public abstract Property<String> getFormat();

    /**
     * Optional content-identity inputs that pin this archive to the current image.
     *
     * <p>{@code podman save} serializes whatever the image tag currently points at in
     * podman storage — state Gradle cannot snapshot from the filesystem — so keying
     * the task only on the tag string would leave the archive stale after a same-tag
     * rebuild. The {@code images { }} DSL wires the image's reference file (which
     * carries the digest, refreshed on every build) in here, so a content change flips
     * the recorded digest and re-runs the save, while an unchanged rebuild leaves it
     * up-to-date. These files are never part of the command line; they exist purely so
     * the up-to-date check tracks the image's content identity rather than just its tag.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getSourceReferenceFiles();

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("save");
        addOption(args, "--format", getFormat().getOrNull());
        addOption(args, "-o", getOutputFile().get().getAsFile().getAbsolutePath());
        args.add(getImage().get());
        return args;
    }
}
