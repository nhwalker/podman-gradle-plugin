package io.github.nhwalker.helm.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Assembles a chart for packaging: it syncs the chart directory into a staging
 * directory, copies any resolved subchart archives into {@code charts/}, and
 * injects build-time {@code preValues} by replacing
 * <code>{{ .PreValues.&lt;name&gt; }}</code> placeholders (whitespace inside the
 * braces is ignored) in the staged {@code Chart.yaml} and {@code values.yaml}.
 *
 * <p>Substitution happens here, before {@code helm package}/{@code helm lint}, so
 * those tasks operate on a pristine staged copy and the user's source tree is left
 * untouched. The {@code preValues} map is a tracked input, so changing a value
 * re-stages the chart.
 */
public abstract class HelmStageTask extends DefaultTask {

    /**
     * Matches <code>{{ .PreValues.Name }}</code> with arbitrary whitespace inside
     * the braces; {@code Name} is captured for lookup in {@link #getPreValues()}.
     */
    private static final Pattern PRE_VALUE = Pattern.compile("\\{\\{\\s*\\.PreValues\\.([A-Za-z0-9_]+)\\s*\\}\\}");

    /** The names of the chart files that pre-values are substituted into. */
    private static final String[] SUBSTITUTED_FILES = {"Chart.yaml", "values.yaml"};

    /** The source chart directory (containing {@code Chart.yaml}). Required. */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getChartDirectory();

    /** Resolved subchart archives to place under {@code charts/}. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getSubchartArchives();

    /** Build-time values substituted into {@code Chart.yaml}/{@code values.yaml}. */
    @Input
    public abstract MapProperty<String, String> getPreValues();

    /** The staging directory assembled by this task. */
    @OutputDirectory
    public abstract DirectoryProperty getStagedDirectory();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void stage() {
        File staged = getStagedDirectory().get().getAsFile();

        // Sync (clears the staging dir) the chart sources, then add subchart archives.
        getFileSystemOperations().sync(spec -> {
            spec.from(getChartDirectory());
            spec.into(staged);
        });
        getFileSystemOperations().copy(spec -> {
            spec.from(getSubchartArchives());
            spec.into(new File(staged, "charts"));
        });

        Map<String, String> values = getPreValues().get();
        if (!values.isEmpty()) {
            for (String fileName : SUBSTITUTED_FILES) {
                substitute(new File(staged, fileName), values);
            }
        }
    }

    /** Rewrites {@code file} (if present) replacing every known pre-value placeholder. */
    private void substitute(File file, Map<String, String> values) {
        if (!file.isFile()) {
            return;
        }
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Matcher matcher = PRE_VALUE.matcher(content);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                // Leave placeholders for unset names untouched rather than failing.
                String replacement = values.getOrDefault(name, matcher.group());
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(result);

            String rewritten = result.toString();
            if (!rewritten.equals(content)) {
                Files.writeString(file.toPath(), rewritten, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inject pre-values into " + file, e);
        }
    }
}
