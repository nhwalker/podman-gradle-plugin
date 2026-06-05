package io.github.nhwalker.podman.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;

/**
 * Exports an image to a tar archive with {@code podman save}.
 *
 * <pre>
 * tasks.register('saveImage', PodmanSaveTask) {
 *     image = 'example/app:latest'
 *     outputFile = layout.buildDirectory.file('images/app.tar')
 * }
 * </pre>
 */
public abstract class PodmanSaveTask extends AbstractPodmanTask {

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
