package io.github.nhwalker.helm.gradle.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * Packages a chart directory into a versioned archive with {@code helm package}.
 *
 * <p>{@code helm} names its output {@code <chartName>-<version>.tgz} from the
 * chart's {@code Chart.yaml}. To give the produced archive a stable, predictable
 * path (so it can be tracked as an output and shared as a dependency variant),
 * this task packages into a private temporary directory and then moves the single
 * resulting {@code .tgz} to {@link #getPackagedChart()}.
 *
 * <pre>
 * tasks.register('packageApi', HelmPackageTask) {
 *     chartDirectory = layout.projectDirectory.dir('src/main/helm/api')
 *     chartVersion   = '1.0.0'
 *     packagedChart  = layout.buildDirectory.file('helm/api/api.tgz')
 * }
 * </pre>
 */
public abstract class HelmPackageTask extends AbstractHelmTask {

    /** The chart directory (containing {@code Chart.yaml}) to package. Required. */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getChartDirectory();

    /** Override the chart version ({@code --version}). */
    @Input
    @Optional
    public abstract Property<String> getChartVersion();

    /** Override the chart {@code appVersion} ({@code --app-version}). */
    @Input
    @Optional
    public abstract Property<String> getAppVersion();

    /** Update {@code Chart.yaml} dependencies before packaging ({@code -u}). Defaults to {@code false}. */
    @Input
    public abstract Property<Boolean> getUpdateDependencies();

    /** Extra raw arguments appended to {@code helm package}. */
    @Input
    public abstract ListProperty<String> getExtraArguments();

    /** The packaged chart archive to write. */
    @OutputFile
    public abstract RegularFileProperty getPackagedChart();

    @SuppressWarnings("this-escape")
    public HelmPackageTask() {
        getUpdateDependencies().convention(false);
        getExtraArguments().convention(List.of());
    }

    /** The private directory {@code helm package} writes its archive into before it is moved. */
    private Path destinationDir() {
        return getTemporaryDir().toPath().resolve("packaged");
    }

    @Override
    protected List<String> buildSubcommand() {
        List<String> args = new ArrayList<>();
        args.add("package");
        args.add(getChartDirectory().get().getAsFile().getAbsolutePath());
        addOption(args, "--version", getChartVersion().getOrNull());
        addOption(args, "--app-version", getAppVersion().getOrNull());
        addFlag(args, "-u", getUpdateDependencies().get());
        args.addAll(getExtraArguments().get());
        args.add("--destination");
        args.add(destinationDir().toString());
        return args;
    }

    @Override
    public void execute() {
        Path dest = destinationDir();
        prepareCleanDirectory(dest);

        runSubcommand(buildSubcommand(), false);

        if (getDryRun().get()) {
            return;
        }

        Path output = getPackagedChart().get().getAsFile().toPath();
        try (Stream<Path> produced = Files.list(dest)) {
            Path archive = produced
                    .filter(p -> p.getFileName().toString().endsWith(".tgz"))
                    .findFirst()
                    .orElseThrow(() -> new GradleException(
                            "helm package produced no .tgz archive in " + dest));
            Files.createDirectories(output.getParent());
            Files.move(archive, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to collect the packaged chart from " + dest, e);
        }
    }

    /** Empties (recreating) {@code dir} so exactly the new archive is found afterwards. */
    private static void prepareCleanDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException("Failed to clean " + p, e);
                        }
                    });
                }
            }
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to prepare package directory " + dir, e);
        }
    }
}
