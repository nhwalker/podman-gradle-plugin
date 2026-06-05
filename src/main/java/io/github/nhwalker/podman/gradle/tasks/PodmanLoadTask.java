package io.github.nhwalker.podman.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * Loads an image from a tar archive with {@code podman load}.
 *
 * <pre>
 * tasks.register('loadImage', PodmanLoadTask) {
 *     inputFile = layout.buildDirectory.file('images/app.tar')
 * }
 * </pre>
 */
public abstract class PodmanLoadTask extends AbstractPodmanTask {

    /** The archive file to import ({@code -i}). Required. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputFile();

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("load");
        addOption(args, "-i", getInputFile().get().getAsFile().getAbsolutePath());
        return args;
    }
}
