package io.github.nhwalker.artifacts.gradle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies;
import io.github.nhwalker.artifacts.gradle.dsl.ConsumedArtifact;
import io.github.nhwalker.artifacts.gradle.dsl.ProducedArtifact;
import io.github.nhwalker.artifacts.gradle.support.ResourceImports;

/**
 * Registers the {@code genericArtifacts} extension and turns each declared producer
 * into a consumable configuration (a variant of the publishable
 * {@code genericArtifacts} software component) and each declared consumer into a
 * dependency bucket + resolvable whose resolved files are exposed back on the DSL.
 *
 * <p>The model generalizes the container/helm plugins' "one module, several
 * attribute-selected variants" pattern into a reusable, artifact-agnostic plugin: a
 * Maven <em>classifier</em> is modeled as a Gradle attribute (plus optional free
 * String attributes), so arbitrary artifacts can be published, consumed, and selected
 * identically in normal builds, composite builds (where Maven classifiers do not
 * survive substitution), and Maven repositories.
 *
 * <p>Apply with:
 * <pre>
 * plugins { id 'io.github.nhwalker.artifacts' }
 * </pre>
 */
public class ArtifactsPlugin implements Plugin<Project> {

    /** The name of the project extension contributed by this plugin. */
    public static final String EXTENSION_NAME = "genericArtifacts";

    /** The name of the software component aggregating this project's artifact variants. */
    public static final String COMPONENT_NAME = "genericArtifacts";

    private final SoftwareComponentFactory softwareComponentFactory;

    @Inject
    public ArtifactsPlugin(SoftwareComponentFactory softwareComponentFactory) {
        this.softwareComponentFactory = softwareComponentFactory;
    }

    @Override
    public void apply(Project project) {
        ArtifactsExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, ArtifactsExtension.class);
        extension.getGenerateReferences().convention(false);
        extension.getReferencesPackage().convention(
                project.provider(() -> String.valueOf(project.getGroup())));

        ArtifactsDependencies.registerSchema(project);

        // One component aggregates every produced artifact's variant (one module/coordinate),
        // the same way the java component carries the main + sources/javadoc jars.
        AdhocComponentWithVariants component = softwareComponentFactory.adhoc(COMPONENT_NAME);
        project.getComponents().add(component);

        // Materialize each declaration once the DSL is fully evaluated, so final
        // classifier/attribute values are seen before configurations are created.
        project.afterEvaluate(p -> {
            extension.getProduce().forEach(artifact -> registerProducer(p, artifact, component));
            extension.getConsume().forEach(artifact -> registerConsumer(p, artifact));
            // When opted in and a Java plugin is applied, expose the resource path of every
            // produced artifact that bundled itself into resources through a generated interface.
            if (extension.getGenerateReferences().get() && p.getPluginManager().hasPlugin("java")) {
                registerReferences(p, extension);
            }
        });
    }

    private void registerReferences(Project project, ArtifactsExtension extension) {
        // Group constants (and the bundle tasks that materialize them) by the source set they
        // target, so each source set gets its own <ProjectName>References[<SourceSet>] interface.
        Map<String, Map<String, Provider<String>>> constantsBySourceSet = new LinkedHashMap<>();
        Map<String, List<TaskProvider<? extends Task>>> bundlesBySourceSet = new LinkedHashMap<>();
        extension.getProduce().forEach(artifact ->
                artifact.getResourceBundles().forEach((sourceSet, bundle) -> {
                    constantsBySourceSet.computeIfAbsent(sourceSet, k -> new LinkedHashMap<>())
                            .put(artifact.getName(), ResourceImports.bundledResourcePath(bundle));
                    bundlesBySourceSet.computeIfAbsent(sourceSet, k -> new ArrayList<>()).add(bundle);
                }));
        // Arbitrary user-declared constants share the same interface as the bundled resource paths.
        extension.getReferences().forEach((sourceSet, container) ->
                container.forEach(reference -> constantsBySourceSet
                        .computeIfAbsent(sourceSet, k -> new LinkedHashMap<>())
                        .put(reference.getName(), reference.getValue())));

        constantsBySourceSet.forEach((sourceSet, constants) ->
                ResourceImports.contributeReferences(project, sourceSet, extension.getReferencesPackage(),
                        constants, bundlesBySourceSet.getOrDefault(sourceSet, List.of())));
    }

    private void registerProducer(Project project, ProducedArtifact artifact,
            AdhocComponentWithVariants component) {
        if (artifact.getArtifactSpecs().isEmpty()) {
            throw new InvalidUserDataException(
                    "produced artifact '" + artifact.getName() + "' must declare at least one artifact(...)");
        }
        String classifier = artifact.getClassifier().get();
        Map<String, String> attributes = artifact.getAttributes().get();
        attributes.keySet().forEach(key -> ArtifactsDependencies.registerAttributeKey(project, key));

        var elements = ArtifactsDependencies.elements(project,
                elementsName(artifact.getName()), classifier, attributes, artifact.getArtifactSpecs());
        component.addVariantsFromConfiguration(elements.get(), details -> { });
    }

    private void registerConsumer(Project project, ConsumedArtifact artifact) {
        // Null classifier => request no classifier attribute. Free attributes are NOT
        // registered in the schema: they may be native names (e.g. org.gradle.docstype)
        // already typed by another plugin, and String values match those by name anyway.
        String classifier = artifact.getClassifier().getOrNull();
        Map<String, String> attributes = artifact.getAttributes().get();

        NamedDomainObjectProvider<DependencyScopeConfiguration> bucket =
                ArtifactsDependencies.dependencyBucket(project, depsName(artifact.getName()));
        artifact.getDependencyNotations().forEach(notation ->
                project.getDependencies().add(bucket.getName(), notation));

        var resolvable = ArtifactsDependencies.resolvable(project,
                refsName(artifact.getName()), bucket, classifier, attributes);
        artifact.getFiles().from(resolvable);
    }

    // ---- configuration-name helpers ---------------------------------------------

    public static String elementsName(String name) {
        return name + "Elements";
    }

    public static String depsName(String name) {
        return name + "Deps";
    }

    public static String refsName(String name) {
        return name + "Refs";
    }
}
