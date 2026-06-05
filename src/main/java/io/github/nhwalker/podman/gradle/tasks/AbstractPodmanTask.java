package io.github.nhwalker.podman.gradle.tasks;

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
 * Base class for tasks that invoke the {@code podman} command line.
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
public abstract class AbstractPodmanTask extends DefaultTask {

    private String capturedStandardOutput;

    /**
     * The podman executable to run. Defaults to {@code "podman"} (resolved on
     * the {@code PATH}); usually configured via the {@code podman} extension.
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
    protected AbstractPodmanTask() {
        getExecutable().convention("podman");
        getIgnoreExitValue().convention(false);
        getDryRun().convention(false);
        getCaptureOutput().convention(false);
    }

    /**
     * The podman subcommand together with its arguments, for example
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
        List<String> command = assembleCommand();
        String rendered = String.join(" ", command);

        if (getDryRun().get()) {
            getLogger().lifecycle("[dry-run] {}", rendered);
            return;
        }

        getLogger().info("Executing: {}", rendered);

        ByteArrayOutputStream captureBuffer = getCaptureOutput().get()
                ? new ByteArrayOutputStream()
                : null;

        ExecResult result = getExecOperations().exec(spec -> {
            spec.commandLine(command);
            spec.setIgnoreExitValue(getIgnoreExitValue().get());
            if (captureBuffer != null) {
                spec.setStandardOutput(captureBuffer);
            }
        });

        if (captureBuffer != null) {
            capturedStandardOutput = captureBuffer.toString(StandardCharsets.UTF_8);
        }

        if (getIgnoreExitValue().get()) {
            getLogger().info("{} exited with code {}", getExecutable().get(), result.getExitValue());
        } else {
            result.assertNormalExitValue();
        }
    }

    /** Assembles the full command line: executable, global options, connection, subcommand. */
    final List<String> assembleCommand() {
        List<String> command = new ArrayList<>();
        command.add(getExecutable().get());
        command.addAll(getGlobalOptions().get());
        if (getConnection().isPresent()) {
            command.add("--connection");
            command.add(getConnection().get());
        }
        command.addAll(buildSubcommand());
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
