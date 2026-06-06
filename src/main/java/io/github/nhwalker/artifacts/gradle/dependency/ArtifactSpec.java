package io.github.nhwalker.artifacts.gradle.dependency;

import org.gradle.api.Action;
import org.gradle.api.artifacts.ConfigurablePublishArtifact;

/**
 * A single artifact to publish on a producer's consumable configuration: the
 * notation (a {@code Provider<RegularFile>}, a {@code File}, a task, or anything
 * {@code ConfigurationPublications.artifact(Object)} accepts) plus an optional
 * configuration block applied to the resulting {@link ConfigurablePublishArtifact}.
 *
 * <p>The configuration block is where build dependencies are set: when the notation
 * already carries them (e.g. a task output {@code Provider}) Gradle infers the
 * producing task automatically; for a plain {@code File} use the block's
 * {@code builtBy(...)} so publishing and resolution still trigger the producer.
 */
public final class ArtifactSpec {

    private final Object notation;
    private final Action<? super ConfigurablePublishArtifact> configuration;

    public ArtifactSpec(Object notation, Action<? super ConfigurablePublishArtifact> configuration) {
        this.notation = notation;
        this.configuration = configuration;
    }

    /** The artifact notation registered on the outgoing configuration. */
    public Object getNotation() {
        return notation;
    }

    /** Optional per-artifact configuration (classifier/type/extension/builtBy); may be {@code null}. */
    public Action<? super ConfigurablePublishArtifact> getConfiguration() {
        return configuration;
    }
}
