package io.github.nhwalker.podman.gradle.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * Builds an image with {@code podman build}.
 *
 * <pre>
 * tasks.register('buildImage', PodmanBuildTask) {
 *     contextDirectory = layout.projectDirectory.dir('src/main/docker')
 *     tags = ['example/app:latest', 'example/app:1.0']
 *     buildArgs = ['VERSION': version.toString()]
 * }
 * </pre>
 */
public abstract class PodmanBuildTask extends AbstractPodmanTask {

    /** Build context directory. Defaults to the project directory. */
    @Internal
    public abstract DirectoryProperty getContextDirectory();

    /** Optional Containerfile/Dockerfile ({@code -f}). */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getContainerfile();

    /** Image tags ({@code -t}). At least one is recommended. */
    @Input
    public abstract ListProperty<String> getTags();

    /** Build arguments ({@code --build-arg KEY=VALUE}). */
    @Input
    public abstract MapProperty<String, String> getBuildArgs();

    /** Image labels ({@code --label KEY=VALUE}). */
    @Input
    public abstract MapProperty<String, String> getLabels();

    /** Target platform, e.g. {@code linux/amd64} ({@code --platform}). */
    @Input
    @Optional
    public abstract Property<String> getPlatform();

    /** Stage to build in a multi-stage Containerfile ({@code --target}). */
    @Input
    @Optional
    public abstract Property<String> getTarget();

    /** Disable the build cache ({@code --no-cache}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getNoCache();

    /** Always attempt to pull newer base images ({@code --pull}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getPull();

    /** Additional raw arguments appended to the build command. */
    @Input
    public abstract ListProperty<String> getExtraArguments();

    @SuppressWarnings("this-escape")
    public PodmanBuildTask() {
        getContextDirectory().convention(getProject().getLayout().getProjectDirectory());
        getNoCache().convention(false);
        getPull().convention(false);
    }

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("build");

        for (String tag : getTags().get()) {
            addOption(args, "-t", tag);
        }
        if (getContainerfile().isPresent()) {
            addOption(args, "-f", getContainerfile().get().getAsFile().getAbsolutePath());
        }
        for (Map.Entry<String, String> e : getBuildArgs().get().entrySet()) {
            addOption(args, "--build-arg", e.getKey() + "=" + e.getValue());
        }
        for (Map.Entry<String, String> e : getLabels().get().entrySet()) {
            addOption(args, "--label", e.getKey() + "=" + e.getValue());
        }
        addOption(args, "--platform", getPlatform().getOrNull());
        addOption(args, "--target", getTarget().getOrNull());
        addFlag(args, "--no-cache", getNoCache().get());
        addFlag(args, "--pull", getPull().get());
        args.addAll(getExtraArguments().get());

        args.add(getContextDirectory().get().getAsFile().getAbsolutePath());
        return args;
    }
}
