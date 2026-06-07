package io.github.nhwalker.artifacts.gradle.dsl;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.Named;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * A single artifact dependency declared in {@code genericArtifacts { consume { } }}.
 *
 * <p>The plugin turns each consumed artifact into a dependency-scope bucket (holding
 * the {@link #from(Object) declared dependencies}) and a resolvable whose requested
 * attributes are <em>exactly</em> what you declare here — nothing by default. It then
 * populates {@link #getFiles()} from that resolvable; wire {@code getFiles()} into your
 * own tasks to consume the resolved artifact.
 *
 * <p>Unlike the producer side, the consumer does <strong>not</strong> request the
 * {@code ecosystem} fence. This is deliberate: a bare request resolves the target's
 * conventional default (e.g. a Java library's main jar), and adding attributes lets the
 * same one API select either a generic artifact published by this plugin or a native
 * variant of any other Gradle project:
 *
 * <pre>
 * genericArtifacts { consume {
 *     // a generic artifact published by this plugin (project / composite / repo)
 *     theReport { from 'com.example:producer:1.0'; classifier = 'report' }
 *
 *     // a native variant of another project — selected by its own attributes
 *     libSources {
 *         from project(':lib')
 *         attribute 'org.gradle.category', 'documentation'
 *         attribute 'org.gradle.docstype', 'sources'
 *     }
 *
 *     // the main jar of another project — no attributes needed
 *     libJar { from project(':lib') }
 *
 *     // a plain Maven-repo artifact by classifier (artifact-only notation)
 *     guavaSources { from 'com.google.guava:guava:33.0.0-jre:sources@jar' }
 * }}
 * tasks.register('useReport') { inputs.files genericArtifacts.consume.theReport.files }
 * </pre>
 */
public abstract class ConsumedArtifact implements Named {

    private final String name;
    private final List<Object> dependencyNotations = new ArrayList<>();

    @Inject
    public ConsumedArtifact(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * Optional. When set, the request carries the
     * {@code io.github.nhwalker.artifacts.classifier} attribute with this value, which
     * selects a generic artifact published by this plugin. Leave unset to request no
     * classifier (use {@link #attribute} for native attributes, or neither to get the
     * target's default artifact).
     */
    public abstract Property<String> getClassifier();

    /**
     * Free String attributes added to the request. May be this plugin's own attributes
     * or any native Gradle attribute (e.g. {@code org.gradle.docstype = sources});
     * String values match typed/{@code Named} producer attributes by name.
     */
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
