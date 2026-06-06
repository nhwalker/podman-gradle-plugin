package io.github.nhwalker.helm.gradle.tasks;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Base class for tasks that invoke the {@code helm} command line.
 *
 * <p>The full command is assembled as:
 * <pre>
 * &lt;executable&gt; &lt;globalOptions&gt; &lt;subcommand&gt;
 * </pre>
 * where {@code subcommand} is provided by each concrete task via
 * {@link #buildSubcommand()}. Execution is delegated to Gradle's
 * {@link ExecOperations} so the plugin is compatible with the configuration
 * cache and never depends on a Helm API binding.
 */
public abstract class AbstractHelmTask extends DefaultTask {

    private String capturedStandardOutput;

    /**
     * The helm executable to run. Defaults to {@code "helm"} (resolved on the
     * {@code PATH}); usually configured via the {@code helm} extension.
     */
    @Input
    public abstract Property<String> getExecutable();

    /**
     * Global options placed between the executable and the subcommand, for
     * example {@code ["--namespace", "platform"]} or {@code ["--kube-context",
     * "staging"]}.
     */
    @Input
    public abstract ListProperty<String> getGlobalOptions();

    /**
     * When {@code true} a non-zero exit code does not fail the task. Defaults to
     * {@code false}.
     */
    @Input
    public abstract Property<Boolean> getIgnoreExitValue();

    /**
     * When {@code true} the assembled command is logged and execution is
     * skipped. Useful for inspecting what the plugin would run. Defaults to
     * {@code false}.
     */
    @Input
    public abstract Property<Boolean> getDryRun();

    /**
     * When {@code true} the process standard output is captured (and made
     * available through {@link #getStandardOutput()}) instead of being streamed
     * to the Gradle console. Defaults to {@code false}.
     */
    @Internal
    public abstract Property<Boolean> getCaptureOutput();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @SuppressWarnings("this-escape")
    protected AbstractHelmTask() {
        getExecutable().convention("helm");
        getIgnoreExitValue().convention(false);
        getDryRun().convention(false);
        getCaptureOutput().convention(false);
    }

    /**
     * The helm subcommand together with its arguments, for example
     * {@code ["lint", "./chart", "--strict"]}. Implemented by concrete tasks.
     *
     * @return the ordered subcommand arguments; never {@code null}
     */
    protected abstract List<String> buildSubcommand();

    /**
     * The standard output captured during the last execution, or {@code null} if
     * {@link #getCaptureOutput()} was not enabled or the task has not run.
     */
    @Internal
    public String getStandardOutput() {
        return capturedStandardOutput;
    }

    @TaskAction
    public void execute() {
        String captured = runSubcommand(buildSubcommand(), getCaptureOutput().get());
        if (captured != null) {
            capturedStandardOutput = captured;
        }
    }

    /**
     * Runs a single helm subcommand using the shared executable and global
     * options. Honors {@link #getDryRun()} (logs and skips) and
     * {@link #getIgnoreExitValue()} (logs instead of failing on a non-zero exit).
     *
     * @param subcommand    the subcommand and its arguments, e.g. {@code ["lint", "./chart"]}
     * @param captureStdout when {@code true} the process standard output is
     *                      captured and returned instead of streamed to the console
     * @return the captured standard output when requested, or {@code null} when
     *         not capturing or when skipped because of {@link #getDryRun()}
     */
    protected String runSubcommand(List<String> subcommand, boolean captureStdout) {
        List<String> command = assembleCommandFor(subcommand);
        String rendered = String.join(" ", command);

        if (getDryRun().get()) {
            getLogger().lifecycle("[dry-run] {}", rendered);
            return null;
        }

        getLogger().info("Executing: {}", rendered);

        ByteArrayOutputStream captureBuffer = captureStdout ? new ByteArrayOutputStream() : null;

        ExecResult result = getExecOperations().exec(spec -> {
            spec.commandLine(command);
            spec.setIgnoreExitValue(getIgnoreExitValue().get());
            if (captureBuffer != null) {
                spec.setStandardOutput(captureBuffer);
            }
        });

        if (getIgnoreExitValue().get()) {
            getLogger().info("{} exited with code {}", getExecutable().get(), result.getExitValue());
        } else {
            result.assertNormalExitValue();
        }

        return captureBuffer != null ? captureBuffer.toString(StandardCharsets.UTF_8) : null;
    }

    /** Assembles the full command line for the task's primary subcommand. */
    final List<String> assembleCommand() {
        return assembleCommandFor(buildSubcommand());
    }

    /** Prepends executable and global options to {@code subcommand}. */
    final List<String> assembleCommandFor(List<String> subcommand) {
        List<String> command = new ArrayList<>();
        command.add(getExecutable().get());
        command.addAll(getGlobalOptions().get());
        command.addAll(subcommand);
        return command;
    }

    // ---- helpers for subclasses -------------------------------------------------

    /** Adds {@code flag value} when {@code value} is non-null and non-blank. */
    protected static void addOption(List<String> args, String flag, String value) {
        if (value != null && !value.isBlank()) {
            args.add(flag);
            args.add(value);
        }
    }

    /** Adds {@code flag} once when {@code enabled} is {@code true}. */
    protected static void addFlag(List<String> args, String flag, boolean enabled) {
        if (enabled) {
            args.add(flag);
        }
    }

    /** Adds a repeated {@code flag value} pair for every entry in {@code values}. */
    protected static void addRepeated(List<String> args, String flag, Iterable<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                args.add(flag);
                args.add(value);
            }
        }
    }
}
