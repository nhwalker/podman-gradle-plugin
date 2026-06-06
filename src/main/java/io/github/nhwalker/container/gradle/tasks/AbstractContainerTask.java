package io.github.nhwalker.container.gradle.tasks;

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
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Base class for tasks that invoke a container engine command line (podman by default).
 *
 * <p>The full command is assembled as:
 * <pre>
 * &lt;executable&gt; &lt;globalOptions&gt; [--connection &lt;name&gt;] &lt;subcommand&gt;
 * </pre>
 * where {@code subcommand} is provided by each concrete task via
 * {@link #buildSubcommand()}. Execution is delegated to Gradle's
 * {@link ExecOperations} so the plugin is compatible with the configuration
 * cache and never depends on a Docker/Podman API binding.
 */
public abstract class AbstractContainerTask extends DefaultTask {

    private String capturedStandardOutput;

    /**
     * The container engine executable to run. Defaults to {@code "podman"} (resolved on
     * the {@code PATH}); usually configured via the {@code container} extension.
     */
    @Input
    public abstract Property<String> getExecutable();

    /** Global options placed between the executable and the subcommand. */
    @Input
    public abstract ListProperty<String> getGlobalOptions();

    /** Optional {@code --connection <name>} for reaching a remote service. */
    @Input
    @Optional
    public abstract Property<String> getConnection();

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
    protected AbstractContainerTask() {
        getExecutable().convention("podman");
        getIgnoreExitValue().convention(false);
        getDryRun().convention(false);
        getCaptureOutput().convention(false);
    }

    /**
     * The container engine subcommand together with its arguments, for example
     * {@code ["build", "-t", "img:latest", "."]}. Implemented by concrete tasks.
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
     * Runs a single container engine subcommand using the shared executable, global
     * options and connection. Honors {@link #getDryRun()} (logs and skips) and
     * {@link #getIgnoreExitValue()} (logs instead of failing on a non-zero exit).
     *
     * <p>This is the primitive that concrete tasks issuing more than one engine
     * invocation (for example {@code tag} or {@code cp}) build on.
     *
     * @param subcommand   the subcommand and its arguments, e.g. {@code ["pull", "img"]}
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

    /** Prepends executable, global options and connection to {@code subcommand}. */
    final List<String> assembleCommandFor(List<String> subcommand) {
        List<String> command = new ArrayList<>();
        command.add(getExecutable().get());
        command.addAll(getGlobalOptions().get());
        if (getConnection().isPresent()) {
            command.add("--connection");
            command.add(getConnection().get());
        }
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
