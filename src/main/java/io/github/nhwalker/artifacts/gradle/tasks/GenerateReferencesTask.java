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
 * {@code API_SERVER}) and its value is the entry value. A single-line value is emitted as a normal
 * string literal; a multi-line value (e.g. a captured text document) is emitted as a Java text block
 * that reproduces it exactly. Entries are sorted for deterministic, diff-friendly output. The
 * generated source lives under {@link #getOutputDirectory()}, which a plugin typically adds to a
 * Java source set so it is compiled with the project.
 *
 * <p>This task is artifact-agnostic: callers supply the interface name, optional package, the
 * name&rarr;value map (values may be lazily computed providers), and an optional generator note.
 */
public abstract class GenerateReferencesTask extends DefaultTask {

    /** The simple name of the generated interface, e.g. {@code FixtureReferences}. */
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
        String loaderName = typeName + "Loader";
        String qualifiedName = packageName.isEmpty() ? typeName : packageName + "." + typeName;

        source.append("// ").append(note).append("\n");
        source.append("public interface ").append(typeName).append(" {\n");
        for (Map.Entry<String, String> entry : constants.entrySet()) {
            appendConstant(source, loaderName, constantName(entry.getKey()), entry.getValue());
        }
        appendLoader(source, loaderName, qualifiedName);
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

    /** Indentation of a text block's content and its closing delimiter (three levels). */
    private static final String TEXT_BLOCK_INDENT = "            ";

    /**
     * Emits {@code public static final String <name> = <loaderName>.load("<name>", <literal>);}. The
     * value is no longer a compile-time constant: it is resolved at class-init time from optional
     * external overrides (see {@link #appendLoader}), falling back to {@code <literal>}. A single-line
     * value uses a normal string literal; a value containing a line break uses a Java text block (a
     * "multiline string"), so captured multi-line documents stay readable in the generated source.
     */
    private static void appendConstant(StringBuilder source, String loaderName, String name, String value) {
        source.append("    public static final String ").append(name).append(" = ")
                .append(loaderName).append(".load(\"").append(name).append("\", ");
        if (value.indexOf('\n') < 0) {
            source.append('"').append(escape(value)).append('"');
        } else {
            appendTextBlock(source, value);
        }
        source.append(");\n");
    }

    /**
     * Renders {@code value} as a Java text block reproducing it exactly, up to and including the
     * closing delimiter (the caller appends any trailing punctuation). The closing delimiter sits on
     * its own line at {@link #TEXT_BLOCK_INDENT} so it pins the incidental-whitespace stripping
     * (preserving the document's own leading indentation); a final {@code \} line-continuation drops
     * the otherwise-added trailing newline when the value does not end in one.
     */
    private static void appendTextBlock(StringBuilder source, String value) {
        source.append("\"\"\"\n");
        // split(-1) keeps trailing empties so String.join("\n", lines) == value exactly.
        String[] lines = value.split("\n", -1);
        boolean trailingNewline = value.endsWith("\n");
        int contentLines = trailingNewline ? lines.length - 1 : lines.length;
        for (int i = 0; i < contentLines; i++) {
            source.append(TEXT_BLOCK_INDENT).append(escapeTextBlockLine(lines[i]));
            if (i == contentLines - 1 && !trailingNewline) {
                source.append('\\');
            }
            source.append('\n');
        }
        source.append(TEXT_BLOCK_INDENT).append("\"\"\"");
    }

    /**
     * Emits the nested {@code <loaderName>} class that backs every constant's {@code load(...)} call,
     * resolving overrides at class-init time with this precedence (highest first):
     * <ol>
     *   <li>system-property files — a comma-separated list of paths in the system property
     *       {@code <qualifiedName>.overrides}; the last file in the list wins;</li>
     *   <li>classpath resources named {@code <qualifiedName>.properties} (a flat, dot-named resource
     *       at the classpath root); an earlier entry on the classpath wins;</li>
     *   <li>the generated default passed to {@code load(name, default)}.</li>
     * </ol>
     * Every member is {@code private} so only the enclosing interface can use it (nestmate access);
     * a missing file or absent property is silent, while an I/O or malformed-properties error on any
     * one source is logged via {@link System.Logger} and that source skipped (non-fatal).
     *
     * <p>{@code qualifiedName} only ever contains identifier characters and dots, but it is routed
     * through {@link #escape} defensively where it is placed into string literals.
     */
    private static void appendLoader(StringBuilder source, String loaderName, String qualifiedName) {
        String qn = escape(qualifiedName);
        source.append('\n').append(String.format(LOADER_TEMPLATE, loaderName, qn));
    }

    /**
     * The body of the nested loader class, indented one level inside the interface. {@code %1$s} is
     * the loader's simple name; {@code %2$s} is the (escaped) qualified interface name, to which
     * {@code .properties}/{@code .overrides} are appended for the resource and system-property names.
     */
    private static final String LOADER_TEMPLATE = """
                class %1$s {
                    private static final java.util.Properties OVERRIDES = loadOverrides();
                    private %1$s() {
                    }
                    private static java.util.Properties loadOverrides() {
                        java.util.Properties overrides = new java.util.Properties();
                        loadClasspath(overrides);
                        loadFiles(overrides);
                        return overrides;
                    }
                    private static void loadClasspath(java.util.Properties overrides) {
                        ClassLoader loader = %1$s.class.getClassLoader();
                        if (loader == null) {
                            loader = ClassLoader.getSystemClassLoader();
                        }
                        java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
                        try {
                            java.util.Enumeration<java.net.URL> found =
                                    loader.getResources("%2$s.properties");
                            while (found.hasMoreElements()) {
                                urls.add(found.nextElement());
                            }
                        } catch (java.io.IOException e) {
                            warn("Failed to enumerate reference overrides", e);
                            return;
                        }
                        for (int i = urls.size() - 1; i >= 0; i--) {
                            java.net.URL url = urls.get(i);
                            try (java.io.InputStream in = url.openStream()) {
                                overrides.load(in);
                            } catch (java.io.IOException | IllegalArgumentException e) {
                                warn("Failed to load reference overrides from " + url, e);
                            }
                        }
                    }
                    private static void loadFiles(java.util.Properties overrides) {
                        String location = System.getProperty("%2$s.overrides");
                        if (location == null || location.isBlank()) {
                            return;
                        }
                        for (String entry : location.split(",")) {
                            String trimmed = entry.strip();
                            if (trimmed.isEmpty()) {
                                continue;
                            }
                            java.nio.file.Path path = java.nio.file.Path.of(trimmed);
                            if (!java.nio.file.Files.isRegularFile(path)) {
                                continue;
                            }
                            try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
                                overrides.load(in);
                            } catch (java.io.IOException | IllegalArgumentException e) {
                                warn("Failed to load reference overrides from " + trimmed, e);
                            }
                        }
                    }
                    private static void warn(String message, Throwable error) {
                        System.getLogger("%2$s")
                                .log(System.Logger.Level.WARNING, message, error);
                    }
                    private static String load(String name, String defaultValue) {
                        return OVERRIDES.getProperty(name, defaultValue);
                    }
                }
            """;

    /** Escapes one line for a string literal: backslash, quote and the control characters. */
    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Escapes one content line of a text block: backslash/quote (so no {@code """} can close it
     * early), tab and carriage return; and converts trailing spaces to {@code \s} so the text
     * block's per-line trailing-whitespace stripping does not drop them.
     */
    private static String escapeTextBlockLine(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        if (end < sb.length()) {
            int trailingSpaces = sb.length() - end;
            sb.setLength(end);
            sb.append("\\s".repeat(trailingSpaces));
        }
        return sb.toString();
    }
}
