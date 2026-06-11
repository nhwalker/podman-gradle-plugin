package io.github.nhwalker.artifacts.gradle.dsl;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileTree;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.artifacts.gradle.support.Names;
import io.github.nhwalker.artifacts.gradle.support.ResourceImports;
import io.github.nhwalker.artifacts.gradle.support.StagingSources;
import io.github.nhwalker.artifacts.gradle.support.SyncTasks;

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
 *     // a native variant of another project — e.g. its sources jar, via a preset
 *     // (or attribute '...','...' for any other native variant)
 *     libSources { from project(':lib'); sources() }
 *
 *     // the main jar of another project — no attributes needed
 *     libJar { from project(':lib') }
 *
 *     // a plain Maven-repo artifact by classifier (artifact-only notation)
 *     guavaSources { from 'com.google.guava:guava:33.0.0-jre:sources@jar' }
 * }}
 * tasks.register('useReport') { inputs.files genericArtifacts.consume.theReport.files }
 * </pre>
 *
 * <p>For the common case of materializing the resolved files on disk, {@link #downloadTask}
 * and {@link #unpackTask} register a {@code Sync} task that stages the artifact (or its
 * extracted archive contents) into {@code build/inputs/<name>} by default:
 * <pre>
 * genericArtifacts { consume {
 *     theDist {
 *         from 'com.example:app:1.0'; classifier = 'dist'
 *         unpackTask { into layout.buildDirectory.dir('app') }   // configure the Sync
 *     }
 * }}
 * tasks.named('assembleSomething') { dependsOn genericArtifacts.consume.theDist.unpackTask() }
 * </pre>
 *
 * <p>To bundle a consumed artifact into a Java project's jar, {@link #importResourcesTask}
 * (verbatim) and {@link #importUnpackedResourcesTask} (extracted) stage the artifact into a
 * generated resource folder and register it on a source set's resources (the {@code main}
 * source set by default). Like the helm chart integration, the folder is added via
 * {@code SourceDirectorySet.srcDir} rather than a plain {@code processResources} copy, so it
 * is also visible on the eclipse classpath when running inside the IDE. The target project must
 * apply the {@code java} plugin:
 * <pre>
 * plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
 * genericArtifacts { consume {
 *     theReport {
 *         from 'com.example:producer:1.0'; classifier = 'report'
 *         importResourcesTask { into 'reports' }      // bundled at reports/&lt;file&gt; in the jar
 *     }
 * }}
 * </pre>
 */
public abstract class ConsumedArtifact implements Named {

    /** Task group for the tasks registered by {@link #downloadTask}/{@link #unpackTask}. */
    public static final String TASK_GROUP = "generic artifacts";

    private final String name;
    private final Project project;
    private final List<Object> dependencyNotations = new ArrayList<>();

    @Inject
    public ConsumedArtifact(String name, Project project) {
        this.name = name;
        this.project = project;
    }

    /** Injected so archive expansion in {@link #unpackTask} stays configuration-cache safe. */
    @Inject
    protected abstract ArchiveOperations getArchiveOperations();

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
     * Preset for a JVM <strong>sources</strong> variant: requests
     * {@code org.gradle.category=documentation} and {@code org.gradle.docstype=sources}.
     * Equivalent to setting those two attributes by hand; the target must expose a
     * sources variant (e.g. the producer applied {@code java { withSourcesJar() }}).
     */
    public void sources() {
        attribute(Category.CATEGORY_ATTRIBUTE.getName(), Category.DOCUMENTATION);
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE.getName(), DocsType.SOURCES);
    }

    /**
     * Preset for a JVM <strong>javadoc</strong> variant: requests
     * {@code org.gradle.category=documentation} and {@code org.gradle.docstype=javadoc}.
     * The target must expose a javadoc variant (e.g. {@code java { withJavadocJar() }}).
     */
    public void javadoc() {
        attribute(Category.CATEGORY_ATTRIBUTE.getName(), Category.DOCUMENTATION);
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE.getName(), DocsType.JAVADOC);
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

    // ---- staging tasks ----------------------------------------------------------

    /**
     * Registers (or returns) a {@code Sync} task staging the resolved files into
     * {@code build/inputs/<name>}. Safe to call anywhere as a dependency handle — e.g.
     * {@code from genericArtifacts.consume.x.downloadTask()} inside another task — because it
     * never reconfigures an already-registered task.
     */
    public TaskProvider<Sync> downloadTask() {
        return downloadTask(null);
    }

    /**
     * Registers a {@code Sync} task that stages the resolved artifact files into a directory
     * ({@code build/inputs/<name>} by default). The producing task is wired automatically via
     * the resolved files' build dependencies.
     *
     * <p>Idempotent: the first call registers the task, later calls return the same
     * {@code TaskProvider} and apply {@code configuration} again. Call this overload from a
     * normal configuration context (e.g. the {@code consume} block); use the no-arg
     * {@link #downloadTask()} as the handle inside other tasks' configuration blocks.
     */
    public TaskProvider<Sync> downloadTask(Action<? super Sync> configuration) {
        return stagingTask(downloadTaskName(name),
                "Stages the '" + name + "' consumed artifact into a directory.",
                StagingSources.of(getFiles()), defaultDestination(), configuration);
    }

    /**
     * Registers (or returns) a {@code Sync} task extracting the resolved archive(s) into
     * {@code build/inputs/<name>}. Safe to call anywhere as a dependency handle (never
     * reconfigures an already-registered task).
     */
    public TaskProvider<Sync> unpackTask() {
        return unpackTask(null);
    }

    /**
     * Registers a {@code Sync} task that extracts the contents of the resolved zip/tar
     * archive(s) into a directory ({@code build/inputs/<name>} by default). The archive
     * format is detected per file from its extension.
     *
     * <p>Idempotent, exactly like {@link #downloadTask(Action)}.
     */
    public TaskProvider<Sync> unpackTask(Action<? super Sync> configuration) {
        // Depend on the resolved archives; copy from the expanded trees (which carry no
        // build dependencies of their own).
        return stagingTask(unpackTaskName(name),
                "Unpacks the '" + name + "' consumed archive into a directory.",
                StagingSources.of(getFiles(), unpackedTrees()), defaultDestination(), configuration);
    }

    // ---- resource-import tasks --------------------------------------------------

    /**
     * Registers (or returns) a {@code Sync} task that copies the resolved artifact file(s) into
     * the {@code main} source set's resources, so they are bundled into the jar. Safe to call
     * anywhere as a dependency handle (never reconfigures an already-registered task).
     */
    public TaskProvider<Sync> importResourcesTask() {
        return importResourcesTask(SourceSet.MAIN_SOURCE_SET_NAME, null);
    }

    /**
     * Registers a {@code Sync} task that copies the resolved artifact file(s) into the
     * {@code main} source set's resources. See {@link #importResourcesTask(String, Action)}.
     */
    public TaskProvider<Sync> importResourcesTask(Action<? super CopySpec> configuration) {
        return importResourcesTask(SourceSet.MAIN_SOURCE_SET_NAME, configuration);
    }

    /**
     * Copies the resolved artifact file(s) into the named source set's resources. See
     * {@link #importResourcesTask(String, Action)}.
     */
    public TaskProvider<Sync> importResourcesTask(String sourceSetName) {
        return importResourcesTask(sourceSetName, null);
    }

    /**
     * Registers a {@code Sync} task that copies the resolved artifact file(s) into the named
     * source set's resources (e.g. {@code main} or {@code test}), bundling them into the jar and
     * surfacing them on the eclipse classpath. The files are staged under
     * {@code build/generated/resources/genericArtifacts/<name>/<sourceSet>} and that folder is
     * registered as a resource source directory; the target project must apply the {@code java}
     * plugin. Files land in the resources root by default; {@code configuration} configures the
     * copy spec, so {@code into('subdir')} nests them under a subdirectory (and
     * {@code include}/{@code exclude}/{@code rename} filter them) while the staged root — the
     * folder registered as a resource directory — stays put.
     *
     * <p>The first call registers the task and applies {@code configuration}; later calls return
     * the same {@code TaskProvider} as an idempotent dependency handle without reconfiguring it.
     */
    public TaskProvider<Sync> importResourcesTask(String sourceSetName, Action<? super CopySpec> configuration) {
        return resourceImportTask(importResourcesTaskName(name, sourceSetName),
                "Imports the '" + name + "' consumed artifact into the '" + sourceSetName + "' resources.",
                StagingSources.of(getFiles()), sourceSetName, configuration);
    }

    /**
     * Registers (or returns) a {@code Sync} task that extracts the resolved archive(s) into the
     * {@code main} source set's resources. Safe to call anywhere as a dependency handle.
     */
    public TaskProvider<Sync> importUnpackedResourcesTask() {
        return importUnpackedResourcesTask(SourceSet.MAIN_SOURCE_SET_NAME, null);
    }

    /**
     * Registers a {@code Sync} task that extracts the resolved archive(s) into the {@code main}
     * source set's resources. See {@link #importUnpackedResourcesTask(String, Action)}.
     */
    public TaskProvider<Sync> importUnpackedResourcesTask(Action<? super CopySpec> configuration) {
        return importUnpackedResourcesTask(SourceSet.MAIN_SOURCE_SET_NAME, configuration);
    }

    /**
     * Extracts the resolved archive(s) into the named source set's resources. See
     * {@link #importUnpackedResourcesTask(String, Action)}.
     */
    public TaskProvider<Sync> importUnpackedResourcesTask(String sourceSetName) {
        return importUnpackedResourcesTask(sourceSetName, null);
    }

    /**
     * Registers a {@code Sync} task that extracts the contents of the resolved zip/tar archive(s)
     * into the named source set's resources, bundling them into the jar. The archive format is
     * detected per file from its extension. Staging location, copy-spec {@code configuration},
     * source-set wiring, idempotency, and the {@code java}-plugin requirement match
     * {@link #importResourcesTask(String, Action)}.
     */
    public TaskProvider<Sync> importUnpackedResourcesTask(String sourceSetName, Action<? super CopySpec> configuration) {
        return resourceImportTask(importUnpackedResourcesTaskName(name, sourceSetName),
                "Imports the unpacked '" + name + "' consumed archive into the '" + sourceSetName + "' resources.",
                StagingSources.of(getFiles(), unpackedTrees()), sourceSetName, configuration);
    }

    /**
     * Builds the per-file archive trees for unpacking. Captures the injected service and file
     * collection (not {@code this}) so the lazy source stays configuration-cache safe.
     */
    private Provider<List<FileTree>> unpackedTrees() {
        ArchiveOperations archives = getArchiveOperations();
        return getFiles().getElements().map(locations ->
                locations.stream()
                        .map(location -> {
                            File archive = location.getAsFile();
                            return isTarArchive(archive.getName())
                                    ? archives.tarTree(archive) : archives.zipTree(archive);
                        })
                        .collect(Collectors.toList()));
    }

    /**
     * Stages the sources into {@code build/generated/resources/genericArtifacts/<name>/<sourceSet>}
     * and registers that folder on the named source set's resources, via the shared
     * {@link ResourceImports#register} helper. {@code configuration} configures the copy spec so it
     * can nest into a subdirectory or filter while the staged root stays put. Later calls return the
     * same task as an idempotent dependency handle.
     */
    private TaskProvider<Sync> resourceImportTask(String taskName, String description,
            StagingSources sources, String sourceSetName, Action<? super CopySpec> configuration) {
        Provider<Directory> destination = project.getLayout().getBuildDirectory()
                .dir("generated/resources/genericArtifacts/" + name + "/" + sourceSetName);
        return ResourceImports.register(project, TASK_GROUP, taskName, description,
                sources, sourceSetName, destination, configuration);
    }

    /**
     * Registers the {@code Sync} task on first call (staging the sources into {@code destination}),
     * or returns the existing one on later calls. A non-null {@code configuration} is applied
     * either way (it configures the task, so re-applying is additive); a null one (the no-arg
     * handle path) never reconfigures an existing task, so it is safe to call from within another
     * task's configuration block. See {@link SyncTasks} for the two configuration conventions.
     */
    private TaskProvider<Sync> stagingTask(String taskName, String description,
            StagingSources sources, Provider<Directory> destination, Action<? super Sync> configuration) {
        TaskProvider<Sync> task = SyncTasks.registerIfAbsent(project.getTasks(), taskName, t -> {
            t.setGroup(TASK_GROUP);
            t.setDescription(description);
            t.dependsOn(sources.getTaskDependencies());
            t.into(destination);
            t.from(sources.getCopySource());
        });
        if (configuration != null) {
            task.configure(configuration);
        }
        return task;
    }

    private Provider<Directory> defaultDestination() {
        return project.getLayout().getBuildDirectory().dir("inputs/" + name);
    }

    public static String downloadTaskName(String name) {
        return "download" + Names.capitalize(name);
    }

    public static String unpackTaskName(String name) {
        return "unpack" + Names.capitalize(name);
    }

    public static String importResourcesTaskName(String name, String sourceSetName) {
        return "import" + Names.capitalize(name) + Names.sourceSetQualifier(sourceSetName) + "Resources";
    }

    public static String importUnpackedResourcesTaskName(String name, String sourceSetName) {
        return "import" + Names.capitalize(name) + Names.sourceSetQualifier(sourceSetName) + "UnpackedResources";
    }

    private static boolean isTarArchive(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2");
    }
}
