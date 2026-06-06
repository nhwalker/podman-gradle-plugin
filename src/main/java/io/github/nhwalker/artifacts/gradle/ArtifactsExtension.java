package io.github.nhwalker.artifacts.gradle;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;

import io.github.nhwalker.artifacts.gradle.dsl.ConsumedArtifact;
import io.github.nhwalker.artifacts.gradle.dsl.ProducedArtifact;

/**
 * Project-level configuration for the Generic Artifacts plugin.
 *
 * <p>The {@code produce} container declares classified artifacts to publish and share
 * as dependencies; the {@code consume} container declares classified artifacts to
 * resolve from other projects or coordinates:
 *
 * <pre>
 * genericArtifacts {
 *     produce {
 *         report { attribute 'flavor', 'html'; artifact tasks.makeReport.outputFile }
 *     }
 *     consume {
 *         theReport { from 'com.example:producer:1.0'; classifier = 'report'; attribute 'flavor', 'html' }
 *     }
 * }
 * </pre>
 */
public abstract class ArtifactsExtension {

    private final NamedDomainObjectContainer<ProducedArtifact> produce;
    private final NamedDomainObjectContainer<ConsumedArtifact> consume;

    @Inject
    public ArtifactsExtension(ObjectFactory objects) {
        this.produce = objects.domainObjectContainer(ProducedArtifact.class,
                name -> objects.newInstance(ProducedArtifact.class, name));
        this.consume = objects.domainObjectContainer(ConsumedArtifact.class,
                name -> objects.newInstance(ConsumedArtifact.class, name));
    }

    /** The classified artifacts this project publishes. */
    public NamedDomainObjectContainer<ProducedArtifact> getProduce() {
        return produce;
    }

    /** Configures the {@link #getProduce() produce} container. */
    public void produce(Action<? super NamedDomainObjectContainer<ProducedArtifact>> action) {
        action.execute(produce);
    }

    /** The classified artifacts this project consumes. */
    public NamedDomainObjectContainer<ConsumedArtifact> getConsume() {
        return consume;
    }

    /** Configures the {@link #getConsume() consume} container. */
    public void consume(Action<? super NamedDomainObjectContainer<ConsumedArtifact>> action) {
        action.execute(consume);
    }
}
