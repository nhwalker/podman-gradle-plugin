package io.github.nhwalker.artifacts.gradle;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.SourceSet;

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

    private final ObjectFactory objects;
    private final NamedDomainObjectContainer<ProducedArtifact> produce;
    private final NamedDomainObjectContainer<ConsumedArtifact> consume;
    // One references container per target source set, created on demand (insertion-ordered).
    private final Map<String, NamedDomainObjectContainer<JavaReference>> references = new LinkedHashMap<>();

    @Inject
    public ArtifactsExtension(ObjectFactory objects, Project project) {
        this.objects = objects;
        // Both elements need the Project to register their resource-import / staging tasks.
        this.produce = objects.domainObjectContainer(ProducedArtifact.class,
                name -> objects.newInstance(ProducedArtifact.class, name, project));
        this.consume = objects.domainObjectContainer(ConsumedArtifact.class,
                name -> objects.newInstance(ConsumedArtifact.class, name, project));
    }

    /**
     * The package the generated {@code <ProjectName>References} interfaces are placed into. Defaults
     * to the project group; when blank the default package is used.
     *
     * <p>When the {@code java} plugin is applied, the plugin generates, per source set, a
     * {@code <ProjectName>References[<SourceSet>]} interface exposing, as
     * {@code public static final String} constants, the jar resource path of each produced artifact
     * that {@link ProducedArtifact#importResourcesTask() bundles into} that source set plus every
     * arbitrary value declared in {@link #references(String, Action) references} for it.
     */
    public abstract Property<String> getReferencesPackage();

    /**
     * The name of the generated interface for the {@code main} source set. Defaults to
     * {@code <ProjectName>References}; override to customize (e.g. {@code 'MyRefs'}). Constants
     * declared for a non-{@code main} source set append the capitalized source-set name (e.g.
     * {@code MyRefsTest}).
     */
    public abstract Property<String> getReferencesClassName();

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
     * The declared reference containers keyed by target source set name (a generic way to put
     * arbitrary strings into a Java file). Each becomes a {@code <ProjectName>References[<SourceSet>]}
     * interface alongside the bundled-artifact resource paths, generated when the {@code java} plugin
     * is applied. Read by the plugin.
     */
    public Map<String, NamedDomainObjectContainer<JavaReference>> getReferences() {
        return references;
    }

    /**
     * Declares arbitrary {@code String} constants for the {@code main} source set's references
     * interface. See {@link #references(String, Action)}.
     */
    public void references(Action<? super NamedDomainObjectContainer<JavaReference>> action) {
        references(SourceSet.MAIN_SOURCE_SET_NAME, action);
    }

    /**
     * Declares arbitrary {@code String} constants for the named source set's references interface
     * (the {@code main} set's interface is {@code <ProjectName>References}, others append the
     * capitalized source-set name). Each entry becomes a {@code public static final String}:
     * <pre>
     * genericArtifacts {
     *     references            { apiBaseUrl { value = 'https://api.example.com' } }  // main
     *     references('test')    { stubUrl    { value = 'http://localhost:8080' } }    // test
     * }
     * </pre>
     */
    public void references(String sourceSetName, Action<? super NamedDomainObjectContainer<JavaReference>> action) {
        action.execute(references.computeIfAbsent(sourceSetName,
                // Custom factory so JavaReference's ObjectFactory is injected (it needs one for fromFile).
                name -> objects.domainObjectContainer(JavaReference.class,
                        elementName -> objects.newInstance(JavaReference.class, elementName))));
    }
}
