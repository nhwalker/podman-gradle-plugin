package io.github.nhwalker.helm.gradle.tasks;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * Examines a chart for possible issues with {@code helm lint}.
 *
 * <pre>
 * tasks.register('lintApi', HelmLintTask) {
 *     chartDirectory = layout.projectDirectory.dir('src/main/helm/api')
 *     strict = true
 *     valuesFiles.from('ci/values.yaml')
 * }
 * </pre>
 */
public abstract class HelmLintTask extends AbstractHelmTask {

    /** The chart directory (containing {@code Chart.yaml}) to lint. Required. */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getChartDirectory();

    /** Fail on lint warnings as well as errors ({@code --strict}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getStrict();

    /** Values files supplied to the lint ({@code --values}). */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getValuesFiles();

    /** Extra raw arguments appended to {@code helm lint}. */
    @Input
    public abstract ListProperty<String> getExtraArguments();

    @SuppressWarnings("this-escape")
    public HelmLintTask() {
        getStrict().convention(false);
        getExtraArguments().convention(List.of());
    }

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("lint");
        args.add(getChartDirectory().get().getAsFile().getAbsolutePath());
        addFlag(args, "--strict", getStrict().get());
        for (File values : getValuesFiles().getFiles()) {
            addOption(args, "--values", values.getAbsolutePath());
        }
        args.addAll(getExtraArguments().get());
        return args;
    }
}
