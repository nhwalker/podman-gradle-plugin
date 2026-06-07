package io.github.nhwalker.artifacts.gradle.dsl;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.artifacts.ConfigurablePublishArtifact;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactSpec;

/**
 * A single classified artifact declared in {@code genericArtifacts { produce { } }}.
 *
 * <p>The plugin turns each produced artifact into a consumable configuration (one
 * variant of the {@code genericArtifacts} software component) carrying the
 * {@code ecosystem}/{@code classifier} attributes plus any free attributes, with the
 * declared {@link #artifact(Object) artifacts} as its outgoing artifacts.
 *
 * <pre>
 * genericArtifacts { produce {
 *     report {                              // classifier defaults to the element name 'report'
 *         attribute 'flavor', 'html'
 *         artifact tasks.makeReport.outputFile          // task output: build dependency inferred
 *         // artifact someFile, { builtBy 'makeReport' } // plain file: set builtBy explicitly
 *     }
 * } }
 * </pre>
 */
public abstract class ProducedArtifact implements Named {

    private final String name;
    private final List<ArtifactSpec> artifactSpecs = new ArrayList<>();

    @Inject
    @SuppressWarnings("this-escape")
    public ProducedArtifact(String name) {
        this.name = name;
        getClassifier().convention(name);
    }

    @Override
    public String getName() {
        return name;
    }

    /** The classifier selecting this artifact within the module. Defaults to the element name. */
    public abstract Property<String> getClassifier();

    /** Free String attributes carried by this artifact's variant (and required of consumers). */
    public abstract MapProperty<String, String> getAttributes();

    /** Adds a single free String attribute. */
    public void attribute(String key, String value) {
        getAttributes().put(key, value);
    }

    /**
     * Declares an artifact to publish. The notation may be a {@code Provider<RegularFile>},
     * a {@code File}, a task, or anything {@code outgoing.artifact(Object)} accepts; when it
     * carries build dependencies (e.g. a task output) the producing task is wired automatically.
     */
    public void artifact(Object notation) {
        artifactSpecs.add(new ArtifactSpec(notation, null));
    }

    /**
     * Declares an artifact and configures the resulting publish artifact, e.g. to set
     * {@code classifier}/{@code type}/{@code extension} or {@code builtBy(...)} for a plain file.
     */
    public void artifact(Object notation, Action<? super ConfigurablePublishArtifact> configuration) {
        artifactSpecs.add(new ArtifactSpec(notation, configuration));
    }

    /** The artifact specs recorded by {@link #artifact}; read by the plugin in {@code afterEvaluate}. */
    public List<ArtifactSpec> getArtifactSpecs() {
        return artifactSpecs;
    }
}
