package io.github.nhwalker.artifacts.gradle.support;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.artifacts.gradle.tasks.GenerateReferencesTask;

/**
 * Shared building blocks for importing artifacts into a Java project's resources and exposing their
 * resource paths through a generated interface. Used by the generic-artifacts produce/consume DSL
 * and by the helm plugin so the bundling and reference-generation logic lives in one place.
 */
public final class ResourceImports {

    private ResourceImports() {
    }

    /**
     * Appends the capitalized source-set name to a base references-interface name for any non-{@code
     * main} source set, leaving {@code main} unsuffixed — so a base of {@code FixtureCharts} yields
     * {@code FixtureCharts} for {@code main} and {@code FixtureChartsTest} for {@code test}.
     */
    public static String withSourceSetSuffix(String baseName, String sourceSetName) {
        return baseName + Names.sourceSetQualifier(sourceSetName);
    }

    /**
     * The conventional base references-interface name: the project name in PascalCase followed by the
     * {@code domain} word ({@code Images}, {@code Charts}, or {@code References}). Each plugin uses its
     * own domain so the generated interfaces do not collide; the result is the {@code main} source
     * set's interface name and the base {@link #withSourceSetSuffix} extends for other source sets.
     */
    public static String defaultReferencesBaseName(String projectName, String domain) {
        return GenerateReferencesTask.pascalCase(projectName) + domain;
    }

    /**
     * The conventional references task name for a source set:
     * {@code generate[<SourceSet>]<Noun>References}, with {@code main} unqualified — e.g. a
     * {@code noun} of {@code Image} yields {@code generateImageReferences} for {@code main} and
     * {@code generateTestImageReferences} for {@code test}. Each plugin supplies its own noun
     * ({@code Image}/{@code Chart}/{@code Artifact}) so the task names do not collide.
     */
    public static String generateReferencesTaskName(String sourceSetName, String noun) {
        return "generate" + Names.sourceSetQualifier(sourceSetName) + noun + "References";
    }

    /**
     * Applies the extension conventions shared by the artifacts/container/helm plugins:
     * {@code referencesPackage} defaults to the project group, {@code referencesClassName} to
     * {@link #defaultReferencesBaseName &lt;ProjectName&gt;&lt;Domain&gt;}, and
     * {@code lifecycleIntegration} to {@code true}. Call from {@code apply}.
     */
    public static void applyExtensionConventions(Project project, ReferencesExtension extension,
            String referencesDomain) {
        extension.getReferencesPackage().convention(
                project.provider(() -> String.valueOf(project.getGroup())));
        extension.getReferencesClassName().convention(project.provider(() ->
                defaultReferencesBaseName(project.getName(), referencesDomain)));
        extension.getLifecycleIntegration().convention(true);
    }

    /**
     * Registers one references interface per populated source set, the shared back half of each
     * plugin's reference generation: the plugin groups its constants (and the tasks that
     * materialize their values) by target source set, and this helper derives the per-source-set
     * class name ({@link #withSourceSetSuffix}), task name ({@code generate[<SourceSet>]<taskNoun>References})
     * and output directory ({@code build/generated/sources/<outputDirName>/java/<sourceSet>}), then
     * delegates to {@link #generateReferences}.
     */
    public static void generateReferencesForSourceSets(Project project, String taskGroup,
            String taskNoun, String outputDirName, String generatedNote, ReferencesExtension extension,
            Map<String, Map<String, Provider<String>>> constantsBySourceSet,
            Map<String, List<TaskProvider<? extends Task>>> regenerateBySourceSet) {
        constantsBySourceSet.forEach((sourceSet, constants) -> {
            String className = withSourceSetSuffix(extension.getReferencesClassName().get(), sourceSet);
            Provider<Directory> output = project.getLayout().getBuildDirectory()
                    .dir("generated/sources/" + outputDirName + "/java/" + sourceSet);
            generateReferences(project, taskGroup, generateReferencesTaskName(sourceSet, taskNoun),
                    className, extension.getReferencesPackage(), generatedNote,
                    constants, output, sourceSet, regenerateBySourceSet.getOrDefault(sourceSet, List.of()));
        });
    }

    /**
     * Registers (or returns) a {@code Sync} task that stages the {@code sources} into
     * {@code destination} and, deferred until the {@code java} plugin is applied, registers that
     * folder onto {@code sourceSetName}'s resources via {@code SourceDirectorySet.srcDir} (so it is
     * bundled into the jar and visible on the eclipse classpath). The {@code srcDir} carries the
     * task as its build dependency. {@code configuration}, when non-null, configures the copy spec
     * so files can be nested into a subdirectory or filtered while the staged root stays put. Later
     * calls return the same task as an idempotent dependency handle without reconfiguring or
     * re-wiring it ({@code configuration} applies to the {@code from} copy spec, which only exists
     * at first registration — see {@link SyncTasks}).
     */
    public static TaskProvider<Sync> register(Project project, String group, String taskName,
            String description, StagingSources sources, String sourceSetName,
            Provider<Directory> destination, Action<? super CopySpec> configuration) {
        TaskContainer tasks = project.getTasks();
        boolean alreadyRegistered = tasks.getNames().contains(taskName);
        TaskProvider<Sync> registered = SyncTasks.registerIfAbsent(tasks, taskName, task -> {
            task.setGroup(group);
            task.setDescription(description);
            task.dependsOn(sources.getTaskDependencies());
            task.into(destination);
            if (configuration != null) {
                task.from(sources.getCopySource(), configuration);
            } else {
                task.from(sources.getCopySource());
            }
        });
        if (!alreadyRegistered) {
            project.getPluginManager().withPlugin("java", applied -> {
                SourceSet sourceSet = project.getExtensions().getByType(SourceSetContainer.class)
                        .getByName(sourceSetName);
                sourceSet.getResources().srcDir(registered.map(Sync::getDestinationDir));
            });
        }
        return registered;
    }

    /**
     * Lazily yields the classpath resource path of the single file staged by {@code bundleTask},
     * relative to its destination root (forward-slash separated). Yields an empty string when the
     * bundle does not contain exactly one file, which the reference generator treats as "skip".
     *
     * <p>The value is rooted in the bundle task's <em>output</em> files (via a file collection) rather
     * than read off the task object directly. A file collection's {@code getElements()} carries the
     * bundle task as a build dependency <em>and</em> is resolved at execution time, so under the
     * configuration cache the staged file exists when the path is computed — a plain
     * {@code bundleTask.map(...)} would be resolved eagerly at store time, against the not-yet-populated
     * destination directory.
     */
    public static Provider<String> bundledResourcePath(ObjectFactory objects, TaskProvider<Sync> bundleTask) {
        ConfigurableFileCollection staged = objects.fileCollection();
        staged.from(bundleTask);
        return staged.getElements().map(ResourceImports::singleStagedResourcePath);
    }

    /**
     * The forward-slash resource path of the single regular file staged under {@code roots} (the bundle
     * task's destination directory), relative to that root; an empty string when exactly one file is
     * not staged (treated as "skip" by the reference generator).
     */
    private static String singleStagedResourcePath(Set<? extends FileSystemLocation> roots) {
        for (FileSystemLocation location : roots) {
            Path root = location.getAsFile().toPath();
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
                if (files.size() == 1) {
                    return root.relativize(files.get(0)).toString().replace(File.separatorChar, '/');
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to inspect bundled resources in " + root, e);
            }
        }
        return "";
    }

    /**
     * Registers a {@link GenerateReferencesTask} producing the {@code className} interface, adds its
     * output directory to {@code javaSourceSetName}'s Java sources (so it compiles with the project),
     * makes it depend on {@code regenerateAfter} (the tasks that materialize the values), and wires it
     * onto the eclipse classpath. Entries whose value provider resolves to an empty string are
     * filtered out, so a bundle with no single resolvable file contributes no constant.
     *
     * <p>Each plugin supplies its own task name, output directory and {@code className} domain, so the
     * container, helm, and generic-artifacts plugins generate distinct, non-colliding interfaces
     * ({@code <ProjectName>Images}/{@code Charts}/{@code References}) that can coexist in one project.
     */
    public static TaskProvider<GenerateReferencesTask> generateReferences(Project project,
            String group, String taskName, String className, Provider<String> packageName,
            String generatedNote, Map<String, Provider<String>> constants,
            Provider<Directory> outputDirectory, String javaSourceSetName,
            List<? extends TaskProvider<? extends Task>> regenerateAfter) {
        TaskProvider<GenerateReferencesTask> generate = project.getTasks().register(taskName,
                GenerateReferencesTask.class, task -> {
                    task.setGroup(group);
                    task.setDescription("Generates the " + className + " references interface.");
                    task.getClassName().set(className);
                    task.getPackageName().set(packageName);
                    task.getGeneratedNote().set(generatedNote);
                    // Put every value, allowing empties: GenerateReferencesTask drops empty values, so
                    // one empty/absent entry no longer collapses the whole map (and a documented empty
                    // reference value contributes no constant).
                    constants.forEach((name, value) -> task.getConstants().put(name, value.orElse("")));
                    task.getOutputDirectory().convention(outputDirectory);
                    regenerateAfter.forEach(task::dependsOn);
                });

        SourceSet sourceSet = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(javaSourceSetName);
        sourceSet.getJava().srcDir(generate.flatMap(GenerateReferencesTask::getOutputDirectory));

        project.getPluginManager().withPlugin("eclipse", applied ->
                project.getTasks().named("eclipseClasspath").configure(task -> {
                    task.dependsOn(generate);
                    regenerateAfter.forEach(task::dependsOn);
                }));
        return generate;
    }
}
