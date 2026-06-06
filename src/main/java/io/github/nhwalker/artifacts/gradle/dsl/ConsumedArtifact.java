package io.github.nhwalker.artifacts.gradle.dsl;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.Named;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * A single classified artifact dependency declared in
 * {@code genericArtifacts { consume { } }}.
 *
 * <p>The plugin turns each consumed artifact into a dependency-scope bucket (holding
 * the {@link #from(Object) declared dependencies}) and a resolvable that requests the
 * {@code ecosystem}/{@code classifier} attributes plus the free attributes, then
 * populates {@link #getFiles()} from that resolvable. Wire {@code getFiles()} into
 * your own tasks to consume the resolved artifact.
 *
 * <pre>
 * genericArtifacts { consume {
 *     theReport {                          // classifier defaults to the element name 'theReport'
 *         from 'com.example:producer:1.0'  // or project(':other')
 *         classifier = 'report'
 *         attribute 'flavor', 'html'
 *     }
 * } }
 * tasks.register('useReport') { inputs.files genericArtifacts.consume.theReport.files }
 * </pre>
 */
public abstract class ConsumedArtifact implements Named {

    private final String name;
    private final List<Object> dependencyNotations = new ArrayList<>();

    @Inject
    @SuppressWarnings("this-escape")
    public ConsumedArtifact(String name) {
        this.name = name;
        getClassifier().convention(name);
    }

    @Override
    public String getName() {
        return name;
    }

    /** The classifier selecting which artifact to resolve. Defaults to the element name. */
    public abstract Property<String> getClassifier();

    /** Free String attributes the resolved variant must match. */
    public abstract MapProperty<String, String> getAttributes();

    /** The resolved artifact files. Populated by the plugin from the resolvable configuration. */
    public abstract ConfigurableFileCollection getFiles();

    /** Adds a single free String attribute to the request. */
    public void attribute(String key, String value) {
        getAttributes().put(key, value);
    }

    /**
     * Declares a dependency to resolve the artifact from. The notation may be a
     * {@code String} coordinate, a {@code project(':x')} dependency, or anything the
     * project {@code dependencies} handler accepts. May be called multiple times.
     */
    public void from(Object dependencyNotation) {
        dependencyNotations.add(dependencyNotation);
    }

    /** The dependency notations recorded by {@link #from}; read by the plugin in {@code afterEvaluate}. */
    public List<Object> getDependencyNotations() {
        return dependencyNotations;
    }
}
