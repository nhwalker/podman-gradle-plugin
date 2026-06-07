package io.github.nhwalker.artifacts.gradle.dsl;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.Named;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/**
 * A single arbitrary {@code String} constant declared in
 * {@code genericArtifacts { references { } }}.
 *
 * <p>Where a produced artifact's {@code importResourcesTask()} contributes the <em>resource path</em>
 * of a bundled file, a {@code references} entry contributes an arbitrary value of your choosing —
 * an endpoint URL, a schema version, an externally-supplied image coordinate, anything. Each entry
 * becomes a {@code public static final String} on the generated {@code <ProjectName>References}
 * interface, named after the element in UPPER_SNAKE_CASE:
 *
 * <pre>
 * genericArtifacts {
 *     references {
 *         apiBaseUrl    { value = 'https://api.example.com' }   // -> API_BASE_URL
 *         schemaVersion { value 'v3' }                          // -> SCHEMA_VERSION
 *     }
 * }
 * </pre>
 *
 * <p>{@link #fromFile(Object)} instead captures the <em>contents</em> of a text file as the value —
 * for example the image-reference file the container plugin publishes, or any other text document.
 * A multi-line document is emitted as a Java text block. The motivating use is capturing a built
 * image's coordinate from another project, paired with a {@code consume} declaration that resolves
 * the published reference artifact:
 *
 * <pre>
 * genericArtifacts {
 *     consume    { appRef   { from project(':app'); classifier = 'app-reference' } }
 *     references { appImage { fromFile genericArtifacts.consume.appRef.files } }
 * }
 * </pre>
 *
 * <p>The value is a lazy {@link Property}, so it may be set from a provider (e.g. a task output or
 * another project's resolved coordinate) and is only realized when the interface is generated.
 */
public abstract class JavaReference implements Named {

    private final String name;
    private final ObjectFactory objects;

    @Inject
    public JavaReference(String name, ObjectFactory objects) {
        this.name = name;
        this.objects = objects;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * The value of the generated constant (named after this element in UPPER_SNAKE_CASE). Required;
     * may be set lazily from a provider. An empty value contributes no constant.
     */
    public abstract Property<String> getValue();

    /** Sets the constant's {@link #getValue() value}. */
    public void value(String value) {
        getValue().set(value);
    }

    /**
     * Captures the text contents of a single file as this constant's {@link #getValue() value},
     * reading it lazily when the interface is generated. {@code notation} is anything a
     * {@code FileCollection} accepts — a {@code Provider<RegularFile>}, a {@code File}, a task
     * output, or another element's resolved {@code files} (e.g. {@code consume.<name>.files}) — and
     * its build dependencies are carried, so the producing/resolving task runs first. The collection
     * must resolve to exactly one file. A single-line file has its trailing line terminator dropped
     * (text files conventionally end with one, and an image coordinate wants no trailing newline); a
     * multi-line document is preserved verbatim, trailing newline and all, and rendered as a Java
     * text block in the generated source.
     */
    public void fromFile(Object notation) {
        ConfigurableFileCollection files = objects.fileCollection();
        files.from(notation);
        // getElements() carries the notation's build dependencies; the transform is a static method
        // reference (capturing no Project), so the value stays configuration-cache friendly.
        getValue().set(files.getElements().map(JavaReference::readSingleFileText));
    }

    private static String readSingleFileText(Set<? extends FileSystemLocation> locations) {
        List<File> regularFiles = new ArrayList<>();
        for (FileSystemLocation location : locations) {
            File file = location.getAsFile();
            if (file.isFile()) {
                regularFiles.add(file);
            }
        }
        if (regularFiles.size() != 1) {
            throw new IllegalStateException(
                    "fromFile expected exactly one file but resolved " + regularFiles.size()
                            + ": " + regularFiles);
        }
        File file = regularFiles.get(0);
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            // Drop a single trailing line terminator only for single-line files (text files
            // conventionally end with one, and an image coordinate wants no trailing newline). A
            // multi-line document keeps its trailing newline so its contents are preserved verbatim.
            String withoutTerminator;
            if (text.endsWith("\r\n")) {
                withoutTerminator = text.substring(0, text.length() - 2);
            } else if (text.endsWith("\n")) {
                withoutTerminator = text.substring(0, text.length() - 1);
            } else {
                return text;
            }
            return withoutTerminator.indexOf('\n') < 0 ? withoutTerminator : text;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read reference file " + file, e);
        }
    }
}
