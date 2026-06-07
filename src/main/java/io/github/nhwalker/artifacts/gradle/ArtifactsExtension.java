package io.github.nhwalker.artifacts.gradle;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import io.github.nhwalker.artifacts.gradle.dsl.ConsumedArtifact;
import io.github.nhwalker.artifacts.gradle.dsl.JavaReference;
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
    private final NamedDomainObjectContainer<JavaReference> references;

    @Inject
    public ArtifactsExtension(ObjectFactory objects, Project project) {
        // Both elements need the Project to register their resource-import / staging tasks.
        this.produce = objects.domainObjectContainer(ProducedArtifact.class,
                name -> objects.newInstance(ProducedArtifact.class, name, project));
        this.consume = objects.domainObjectContainer(ConsumedArtifact.class,
                name -> objects.newInstance(ConsumedArtifact.class, name, project));
        // References only carry a name + value, so the default element factory suffices.
        this.references = objects.domainObjectContainer(JavaReference.class);
    }

    /**
     * When {@code true} and the {@code java} plugin is applied, generates a
     * {@code <ProjectName>Artifacts} Java interface exposing, as {@code public static final String}
     * constants, the jar resource path of each produced artifact that
     * {@link ProducedArtifact#importResourcesTask() bundles into resources} plus every arbitrary
     * value declared in {@link #getReferences() references}. Defaults to {@code false}.
     */
    public abstract Property<Boolean> getGenerateReferences();

    /**
     * The package the generated {@code <ProjectName>Artifacts} interface is placed into. Defaults
     * to the project group; when blank the default package is used.
     */
    public abstract Property<String> getReferencesPackage();

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

    /**
     * Arbitrary {@code String} constants to expose on the generated {@code <ProjectName>Artifacts}
     * interface (a generic way to put strings into a Java file), alongside the bundled-artifact
     * resource paths. Realized only when {@link #getGenerateReferences() generateReferences} is on.
     */
    public NamedDomainObjectContainer<JavaReference> getReferences() {
        return references;
    }

    /** Configures the {@link #getReferences() references} container. */
    public void references(Action<? super NamedDomainObjectContainer<JavaReference>> action) {
        action.execute(references);
    }
}
