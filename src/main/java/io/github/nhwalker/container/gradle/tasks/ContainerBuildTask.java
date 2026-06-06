package io.github.nhwalker.container.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import io.github.nhwalker.container.gradle.dsl.BaseImageReference;

/**
 * Builds an image with {@code podman build}.
 *
 * <pre>
 * tasks.register('buildImage', ContainerBuildTask) {
 *     contextDirectory = layout.projectDirectory.dir('src/main/docker')
 *     tags = ['example/app:latest', 'example/app:1.0']
 *     buildArgs = ['VERSION': version.toString()]
 * }
 * </pre>
 */
public abstract class ContainerBuildTask extends AbstractContainerTask {

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

    /**
     * Base images this build depends on. Each entry injects a {@code --build-arg
     * <argName>=<reference>} read at execution time from the resolved reference
     * file, and carries the producer task dependency so the base image is built
     * first. Normally populated by the {@code images { }} DSL via {@code from(...)}.
     */
    @Nested
    public abstract ListProperty<BaseImageReference> getBaseImages();

    @SuppressWarnings("this-escape")
    public ContainerBuildTask() {
        getContextDirectory().convention(getProject().getLayout().getProjectDirectory());
        getNoCache().convention(false);
        getPull().convention(false);
    }

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("build");

        // Base-image references first so explicit user build-args can override them.
        for (BaseImageReference base : getBaseImages().get()) {
            String ref = readReference(base);
            if (ref != null) {
                addOption(args, "--build-arg", base.getArgName().get() + "=" + ref);
            }
        }

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

    /** Reads the first line (the image coordinate) of a base image's resolved reference file. */
    private static String readReference(BaseImageReference base) {
        Set<File> files = base.getReferenceFiles().getFiles();
        if (files.isEmpty()) {
            return null;
        }
        File file = files.iterator().next();
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read base image reference " + file, e);
        }
    }
}

