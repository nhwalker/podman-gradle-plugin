package io.github.nhwalker.podman.gradle;

import javax.inject.Inject;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;

import io.github.nhwalker.podman.gradle.dependency.PodmanAttributes;
import io.github.nhwalker.podman.gradle.dependency.PodmanDependencies;
import io.github.nhwalker.podman.gradle.dsl.PodmanImage;
import io.github.nhwalker.podman.gradle.tasks.AbstractPodmanTask;
import io.github.nhwalker.podman.gradle.tasks.PodmanBuildTask;
import io.github.nhwalker.podman.gradle.tasks.PodmanImageReferenceTask;
import io.github.nhwalker.podman.gradle.tasks.PodmanSaveTask;

/**
 * Registers the {@code podman} extension, applies its configuration as conventions
 * to every podman task, and turns each declared image into build/reference/save
 * tasks plus the consumable configurations and software-component variants used to
 * share images as dependencies between projects.
 *
 * <p>Apply with:
 * <pre>
 * plugins { id 'io.github.nhwalker.podman' }
 * </pre>
 */
public class PodmanPlugin implements Plugin<Project> {

    /** The name of the project extension contributed by this plugin. */
    public static final String EXTENSION_NAME = "podman";

    /** The task group applied to every podman task. */
    public static final String TASK_GROUP = "podman";

    /** The name of the software component aggregating this project's image variants. */
    public static final String COMPONENT_NAME = "podman";

    private final SoftwareComponentFactory softwareComponentFactory;

    @Inject
    public PodmanPlugin(SoftwareComponentFactory softwareComponentFactory) {
        this.softwareComponentFactory = softwareComponentFactory;
    }

    @Override
    public void apply(Project project) {
        PodmanExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, PodmanExtension.class);
        extension.getExecutable().convention("podman");

        project.getTasks().withType(AbstractPodmanTask.class).configureEach(task -> {
            task.setGroup(TASK_GROUP);
            task.getExecutable().convention(extension.getExecutable());
            task.getGlobalOptions().convention(extension.getGlobalOptions());
            task.getConnection().convention(extension.getConnection());
        });

        PodmanDependencies.registerSchema(project);

        // One component aggregates every image's variants (one module/coordinate),
        // the same way the java component carries the main + sources/javadoc jars.
        AdhocComponentWithVariants component = softwareComponentFactory.adhoc(COMPONENT_NAME);
        project.getComponents().add(component);

        // Materialize each image's tasks/configs once the DSL is fully evaluated, so
        // structural decisions (e.g. whether an archive variant exists) see final values.
        project.afterEvaluate(p -> extension.getImages().forEach(image -> registerImage(p, image, component)));
    }

    private void registerImage(Project project, PodmanImage image, AdhocComponentWithVariants component) {
        String name = image.getName();
        if (image.getTags().getOrElse(java.util.List.of()).isEmpty()) {
            throw new InvalidUserDataException(
                    "podman image '" + name + "' must declare at least one tag");
        }
        ProjectLayout layout = project.getLayout();
        Provider<String> primaryTag = image.getTags().map(tags -> tags.get(0));

        var buildTask = project.getTasks().register(PodmanImage.buildTaskName(name), PodmanBuildTask.class, t -> {
            t.getContainerfile().convention(image.getContainerfile());
            t.getContextDirectory().convention(image.getContextDirectory());
            t.getTags().convention(image.getTags());
            t.getBuildArgs().convention(image.getBuildArgs());
            t.getLabels().convention(image.getLabels());
            t.getPlatform().convention(image.getPlatform());
            t.getTarget().convention(image.getTarget());
            t.getNoCache().convention(image.getNoCache());
            t.getPull().convention(image.getPull());
            t.getBaseImages().convention(image.getBaseImages());
        });

        var referenceTask = project.getTasks().register(
                PodmanImage.referenceTaskName(name), PodmanImageReferenceTask.class, t -> {
                    t.dependsOn(buildTask);
                    t.getImageReference().convention(primaryTag);
                    t.getIncludeDigest().convention(image.getIncludeDigest());
                    t.getReferenceFile().convention(
                            layout.getBuildDirectory().file(PodmanImage.referenceFilePath(name)));
                });

        var referenceElements = PodmanDependencies.referenceElements(project,
                PodmanImage.referenceElementsName(name), name,
                referenceTask.flatMap(PodmanImageReferenceTask::getReferenceFile), referenceTask);
        component.addVariantsFromConfiguration(referenceElements.get(), details -> { });

        if (image.getCreateArchive().get()) {
            String format = image.getArchiveFormat().getOrElse(PodmanAttributes.ARCHIVE_FORMAT_OCI);
            var saveTask = project.getTasks().register(PodmanImage.saveTaskName(name), PodmanSaveTask.class, t -> {
                t.dependsOn(buildTask);
                t.getImage().convention(primaryTag);
                t.getFormat().convention(image.getArchiveFormat());
                t.getOutputFile().convention(
                        layout.getBuildDirectory().file(PodmanImage.archiveFilePath(name, format)));
            });
            var archiveElements = PodmanDependencies.archiveElements(project,
                    PodmanImage.archiveElementsName(name), name, format,
                    saveTask.flatMap(PodmanSaveTask::getOutputFile), saveTask);
            component.addVariantsFromConfiguration(archiveElements.get(), details -> { });
        }
    }
}
