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
import org.gradle.api.file.FileTree;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

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
        ConfigurableFileCollection files = getFiles();
        return stagingTask(downloadTaskName(name),
                "Stages the '" + name + "' consumed artifact into a directory.",
                files, files, null);
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
        ConfigurableFileCollection files = getFiles();
        return stagingTask(downloadTaskName(name),
                "Stages the '" + name + "' consumed artifact into a directory.",
                files, files, configuration);
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
        // Capture the service + file collection (not `this`) so the lazy source is CC-safe.
        ArchiveOperations archives = getArchiveOperations();
        ConfigurableFileCollection files = getFiles();
        Provider<List<FileTree>> trees = files.getElements().map(locations ->
                locations.stream()
                        .map(location -> {
                            File archive = location.getAsFile();
                            return isTarArchive(archive.getName())
                                    ? archives.tarTree(archive) : archives.zipTree(archive);
                        })
                        .collect(Collectors.toList()));
        return stagingTask(unpackTaskName(name),
                "Unpacks the '" + name + "' consumed archive into a directory.",
                files, trees, configuration);
    }

    /**
     * Registers the {@code Sync} task on first call (default destination, depending on
     * {@code buildDependency} and copying from {@code source}), or returns the existing one on
     * later calls. A non-null {@code configuration} is applied either way; a null one (the no-arg
     * handle path) never reconfigures an existing task, so it is safe to call from within another
     * task's configuration block.
     */
    private TaskProvider<Sync> stagingTask(String taskName, String description,
            Object buildDependency, Object source, Action<? super Sync> configuration) {
        TaskContainer tasks = project.getTasks();
        if (tasks.getNames().contains(taskName)) {
            TaskProvider<Sync> existing = tasks.named(taskName, Sync.class);
            if (configuration != null) {
                existing.configure(configuration);
            }
            return existing;
        }
        return tasks.register(taskName, Sync.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription(description);
            task.dependsOn(buildDependency);
            task.into(defaultDestination());
            task.from(source);
            if (configuration != null) {
                configuration.execute(task);
            }
        });
    }

    private Provider<org.gradle.api.file.Directory> defaultDestination() {
        return project.getLayout().getBuildDirectory().dir("inputs/" + name);
    }

    public static String downloadTaskName(String name) {
        return "download" + capitalize(name);
    }

    public static String unpackTaskName(String name) {
        return "unpack" + capitalize(name);
    }

    private static boolean isTarArchive(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2");
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
