package io.github.nhwalker.artifacts.gradle.support;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.Directory;
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
     * The conventional references-interface name for a project + source set: the project name in
     * PascalCase followed by {@code References}, with the capitalized source-set name appended for
     * any non-{@code main} source set (e.g. {@code FixtureReferences}, {@code FixtureReferencesTest}).
     * Shared by the container, helm, and generic-artifacts plugins so every generated interface
     * follows one naming scheme.
     */
    public static String referencesClassName(String projectName, String sourceSetName) {
        String base = GenerateReferencesTask.pascalCase(projectName) + "References";
        if (SourceSet.MAIN_SOURCE_SET_NAME.equals(sourceSetName)) {
            return base;
        }
        return base + Character.toUpperCase(sourceSetName.charAt(0)) + sourceSetName.substring(1);
    }

    /**
     * Registers (or returns) a {@code Sync} task that stages {@code source} into {@code destination}
     * and, deferred until the {@code java} plugin is applied, registers that folder onto
     * {@code sourceSetName}'s resources via {@code SourceDirectorySet.srcDir} (so it is bundled into
     * the jar and visible on the eclipse classpath). The {@code srcDir} carries the task as its
     * build dependency. {@code configuration}, when non-null, configures the copy spec so files can
     * be nested into a subdirectory or filtered while the staged root stays put. Later calls return
     * the same task as an idempotent dependency handle without reconfiguring or re-wiring it.
     */
    public static TaskProvider<Sync> register(Project project, String group, String taskName,
            String description, Object buildDependency, Object source, String sourceSetName,
            Provider<Directory> destination, Action<? super CopySpec> configuration) {
        TaskContainer tasks = project.getTasks();
        if (tasks.getNames().contains(taskName)) {
            return tasks.named(taskName, Sync.class);
        }
        TaskProvider<Sync> registered = tasks.register(taskName, Sync.class, task -> {
            task.setGroup(group);
            task.setDescription(description);
            task.dependsOn(buildDependency);
            task.into(destination);
            if (configuration != null) {
                task.from(source, configuration);
            } else {
                task.from(source);
            }
        });
        project.getPluginManager().withPlugin("java", applied -> {
            SourceSet sourceSet = project.getExtensions().getByType(SourceSetContainer.class)
                    .getByName(sourceSetName);
            sourceSet.getResources().srcDir(registered.map(Sync::getDestinationDir));
        });
        return registered;
    }

    /**
     * Lazily yields the classpath resource path of the single file staged by {@code bundleTask},
     * relative to its destination root (forward-slash separated). Yields an empty string when the
     * bundle does not contain exactly one file, which the reference generator treats as "skip".
     */
    public static Provider<String> bundledResourcePath(TaskProvider<Sync> bundleTask) {
        return bundleTask.map(task -> {
            Path root = task.getDestinationDir().toPath();
            if (!Files.isDirectory(root)) {
                return "";
            }
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
                if (files.size() != 1) {
                    return "";
                }
                return root.relativize(files.get(0)).toString().replace(File.separatorChar, '/');
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to inspect bundled resources in " + root, e);
            }
        });
    }

    /**
     * Registers a {@link GenerateReferencesTask} producing the {@code className} interface, adds its
     * output directory to {@code javaSourceSetName}'s Java sources (so it compiles with the
     * project), makes it depend on {@code regenerateAfter} (the tasks that materialize the values),
     * and wires it onto the eclipse classpath. Entries whose value provider resolves to an empty
     * string are filtered out, so a bundle with no single resolvable file contributes no constant.
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
                    constants.forEach((name, value) -> task.getConstants().put(name,
                            value.map(path -> path.isEmpty() ? null : path)));
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
