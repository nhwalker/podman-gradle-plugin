package io.github.nhwalker.artifacts.gradle.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/**
 * Generates a Java interface exposing a set of named {@code String} values as
 * {@code public static final String} constants — for example the classpath resource path of a
 * bundled artifact, or a container image reference.
 *
 * <p>The interface name is taken verbatim from {@link #getClassName()}; each constant's name is
 * the entry key converted to UPPER_SNAKE_CASE (so {@code apiServer} or {@code api-server} become
 * {@code API_SERVER}) and its value is the entry value. Entries are sorted for deterministic,
 * diff-friendly output. The generated source lives under {@link #getOutputDirectory()}, which a
 * plugin typically adds to a Java source set so it is compiled with the project.
 *
 * <p>This task is artifact-agnostic: callers supply the interface name, optional package, the
 * name&rarr;value map (values may be lazily computed providers), and an optional generator note.
 */
public abstract class GenerateReferencesTask extends DefaultTask {

    /** The simple name of the generated interface, e.g. {@code FixtureCharts}. */
    @Input
    public abstract Property<String> getClassName();

    /** The package the interface is generated into. When unset/blank, the default package is used. */
    @Input
    @Optional
    public abstract Property<String> getPackageName();

    /** Maps each entry name (turned into a constant) to its String value. */
    @Input
    public abstract MapProperty<String, String> getConstants();

    /**
     * The first comment line emitted above the interface (without the leading {@code //}). When
     * unset a generic note is used.
     */
    @Input
    @Optional
    public abstract Property<String> getGeneratedNote();

    /** The root directory the {@code .java} file is written into (a source root). */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void generate() {
        String typeName = getClassName().get();
        String packageName = sanitizePackage(getPackageName().getOrElse(""));
        String note = getGeneratedNote().getOrElse("Generated. Do not edit.");
        // Sort for a deterministic, diff-friendly output independent of declaration order.
        Map<String, String> constants = new TreeMap<>(getConstants().get());

        StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append("// ").append(note).append("\n");
        source.append("public interface ").append(typeName).append(" {\n");
        for (Map.Entry<String, String> entry : constants.entrySet()) {
            source.append("    public static final String ")
                    .append(constantName(entry.getKey()))
                    .append(" = \"").append(escape(entry.getValue())).append("\";\n");
        }
        source.append("}\n");

        Path root = getOutputDirectory().get().getAsFile().toPath();
        Path target = packageName.isEmpty()
                ? root.resolve(typeName + ".java")
                : root.resolve(packageName.replace('.', '/')).resolve(typeName + ".java");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, source.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write generated references " + target, e);
        }
    }

    /**
     * Converts a name to PascalCase, suitable for use as (the base of) a generated type name. For
     * example {@code podman-gradle-plugin} becomes {@code PodmanGradlePlugin}; a leading digit is
     * prefixed with {@code _}.
     */
    public static String pascalCase(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            } else {
                upperNext = true;
            }
        }
        if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    /** Converts a name to an UPPER_SNAKE_CASE Java constant identifier. */
    public static String constantName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                boolean boundary = Character.isUpperCase(c) && i > 0
                        && (Character.isLowerCase(name.charAt(i - 1))
                                || Character.isDigit(name.charAt(i - 1)));
                if (boundary && sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toUpperCase(c));
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }
        if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    /** Keeps only valid Java identifier segments, dropping anything that can't form a package. */
    private static String sanitizePackage(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String segment : value.strip().split("\\.")) {
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (Character.isJavaIdentifierPart(c)) {
                    if (clean.length() == 0 && !Character.isJavaIdentifierStart(c)) {
                        clean.append('_');
                    }
                    clean.append(c);
                }
            }
            if (clean.length() > 0) {
                if (result.length() > 0) {
                    result.append('.');
                }
                result.append(clean);
            }
        }
        return result.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
