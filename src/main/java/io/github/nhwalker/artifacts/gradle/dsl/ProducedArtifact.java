package io.github.nhwalker.artifacts.gradle.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurablePublishArtifact;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactSpec;
import io.github.nhwalker.artifacts.gradle.support.ResourceImports;

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
 *
 * <p>{@link #importResourcesTask} additionally bundles the produced artifact into this project's
 * own jar resources (mirroring the consume side). Combined with the extension's
 * {@code generateReferences} option, the bundled resource path is exposed to Java code through a
 * generated {@code <ProjectName>Artifacts} interface:
 * <pre>
 * genericArtifacts {
 *     generateReferences = true
 *     produce { report { artifact tasks.makeReport.outputFile; importResourcesTask() } }
 * }
 * </pre>
 */
public abstract class ProducedArtifact implements Named {

    /** Task group for the tasks registered by {@link #importResourcesTask}. */
    public static final String TASK_GROUP = "generic artifacts";

    private final String name;
    private final Project project;
    private final List<ArtifactSpec> artifactSpecs = new ArrayList<>();
    private TaskProvider<Sync> resourceBundle;

    @Inject
    @SuppressWarnings("this-escape")
    public ProducedArtifact(String name, Project project) {
        this.name = name;
        this.project = project;
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

    // ---- resource bundling ------------------------------------------------------

    /**
     * Registers (or returns) a {@code Sync} task that copies the produced artifact file(s) into the
     * {@code main} source set's resources, so they are bundled into the jar. Safe to call anywhere
     * as a dependency handle.
     */
    public TaskProvider<Sync> importResourcesTask() {
        return importResourcesTask(SourceSet.MAIN_SOURCE_SET_NAME, null);
    }

    /**
     * Registers a {@code Sync} task that copies the produced artifact file(s) into the {@code main}
     * source set's resources. See {@link #importResourcesTask(String, Action)}.
     */
    public TaskProvider<Sync> importResourcesTask(Action<? super CopySpec> configuration) {
        return importResourcesTask(SourceSet.MAIN_SOURCE_SET_NAME, configuration);
    }

    /**
     * Copies the produced artifact file(s) into the named source set's resources. See
     * {@link #importResourcesTask(String, Action)}.
     */
    public TaskProvider<Sync> importResourcesTask(String sourceSetName) {
        return importResourcesTask(sourceSetName, null);
    }

    /**
     * Registers a {@code Sync} task that copies this artifact's declared {@link #artifact(Object)
     * files} into the named source set's resources (e.g. {@code main} or {@code test}), bundling
     * them into the jar and surfacing them on the eclipse classpath. The files are staged under
     * {@code build/generated/resources/genericArtifacts/<name>/<sourceSet>} and that folder is
     * registered as a resource source directory; the target project must apply the {@code java}
     * plugin. Files land in the resources root by default; {@code configuration} configures the
     * copy spec, so {@code into('subdir')} nests them under a subdirectory while the staged root
     * stays put. When the extension's {@code generateReferences} is enabled, the bundled resource
     * path is exposed as a constant on the generated interface.
     *
     * <p>The first call registers the task and applies {@code configuration}; later calls return
     * the same {@code TaskProvider} as an idempotent dependency handle. (A bundling element name
     * must be unique across {@code produce} and {@code consume}.)
     */
    public TaskProvider<Sync> importResourcesTask(String sourceSetName, Action<? super CopySpec> configuration) {
        Provider<Directory> destination = project.getLayout().getBuildDirectory()
                .dir("generated/resources/genericArtifacts/" + name + "/" + sourceSetName);
        // Source from the declared notations lazily so call order in the produce block is irrelevant
        // and build dependencies flow from the task-output providers (configuration-cache safe).
        ConfigurableFileCollection source = project.getObjects().fileCollection();
        source.from((Callable<List<Object>>) () -> artifactSpecs.stream()
                .map(ArtifactSpec::getNotation).collect(Collectors.toList()));
        TaskProvider<Sync> task = ResourceImports.register(project, TASK_GROUP,
                importResourcesTaskName(name, sourceSetName),
                "Bundles the produced '" + name + "' artifact into the '" + sourceSetName + "' resources.",
                source, source, sourceSetName, destination, configuration);
        if (resourceBundle == null) {
            resourceBundle = task;
        }
        return task;
    }

    /**
     * The bundling task registered by the first {@link #importResourcesTask} call, or {@code null}
     * if this artifact does not bundle its files into resources. Read by the plugin to build the
     * generated references interface.
     */
    public TaskProvider<Sync> getResourceBundle() {
        return resourceBundle;
    }

    public static String importResourcesTaskName(String name, String sourceSetName) {
        return "import" + capitalize(name) + sourceSetQualifier(sourceSetName) + "Resources";
    }

    /** Empty for the conventional {@code main} source set, otherwise the capitalized name. */
    private static String sourceSetQualifier(String sourceSetName) {
        return SourceSet.MAIN_SOURCE_SET_NAME.equals(sourceSetName) ? "" : capitalize(sourceSetName);
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
