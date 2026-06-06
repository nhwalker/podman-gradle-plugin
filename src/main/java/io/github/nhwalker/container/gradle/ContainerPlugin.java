package io.github.nhwalker.container.gradle;

import javax.inject.Inject;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;

import io.github.nhwalker.container.gradle.dependency.ContainerAttributes;
import io.github.nhwalker.container.gradle.dependency.ContainerDependencies;
import io.github.nhwalker.container.gradle.dsl.ContainerImage;
import io.github.nhwalker.container.gradle.tasks.AbstractContainerTask;
import io.github.nhwalker.container.gradle.tasks.ContainerBuildTask;
import io.github.nhwalker.container.gradle.tasks.ContainerImageReferenceTask;
import io.github.nhwalker.container.gradle.tasks.ContainerSaveTask;

/**
 * Registers the {@code container} extension, applies its configuration as conventions
 * to every container task, and turns each declared image into build/reference/save
 * tasks plus the consumable configurations and software-component variants used to
 * share images as dependencies between projects.
 *
 * <p>Apply with:
 * <pre>
 * plugins { id 'io.github.nhwalker.container' }
 * </pre>
 */
public class ContainerPlugin implements Plugin<Project> {

    /** The name of the project extension contributed by this plugin. */
    public static final String EXTENSION_NAME = "container";

    /** The task group applied to every container task. */
    public static final String TASK_GROUP = "container";

    /** The name of the software component aggregating this project's image variants. */
    public static final String COMPONENT_NAME = "container";

    private final SoftwareComponentFactory softwareComponentFactory;

    @Inject
    public ContainerPlugin(SoftwareComponentFactory softwareComponentFactory) {
        this.softwareComponentFactory = softwareComponentFactory;
    }

    @Override
    public void apply(Project project) {
        ContainerExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, ContainerExtension.class);
        extension.getExecutable().convention("podman");

        project.getTasks().withType(AbstractContainerTask.class).configureEach(task -> {
            task.setGroup(TASK_GROUP);
            task.getExecutable().convention(extension.getExecutable());
            task.getGlobalOptions().convention(extension.getGlobalOptions());
            task.getConnection().convention(extension.getConnection());
        });

        ContainerDependencies.registerSchema(project);

        // One component aggregates every image's variants (one module/coordinate),
        // the same way the java component carries the main + sources/javadoc jars.
        AdhocComponentWithVariants component = softwareComponentFactory.adhoc(COMPONENT_NAME);
        project.getComponents().add(component);

        // Materialize each image's tasks/configs once the DSL is fully evaluated, so
        // structural decisions (e.g. whether an archive variant exists) see final values.
        project.afterEvaluate(p -> extension.getImages().forEach(image -> registerImage(p, image, component)));
    }

    private void registerImage(Project project, ContainerImage image, AdhocComponentWithVariants component) {
        String name = image.getName();
        if (image.getTags().getOrElse(java.util.List.of()).isEmpty()) {
            throw new InvalidUserDataException(
                    "container image '" + name + "' must declare at least one tag");
        }
        ProjectLayout layout = project.getLayout();
        Provider<String> primaryTag = image.getTags().map(tags -> tags.get(0));

        var buildTask = project.getTasks().register(ContainerImage.buildTaskName(name), ContainerBuildTask.class, t -> {
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
                ContainerImage.referenceTaskName(name), ContainerImageReferenceTask.class, t -> {
                    t.dependsOn(buildTask);
                    t.getImageReference().convention(primaryTag);
                    t.getIncludeDigest().convention(image.getIncludeDigest());
                    t.getReferenceFile().convention(
                            layout.getBuildDirectory().file(ContainerImage.referenceFilePath(name)));
                });

        var referenceElements = ContainerDependencies.referenceElements(project,
                ContainerImage.referenceElementsName(name), name,
                referenceTask.flatMap(ContainerImageReferenceTask::getReferenceFile), referenceTask);
        component.addVariantsFromConfiguration(referenceElements.get(), details -> { });

        if (image.getCreateArchive().get()) {
            String format = image.getArchiveFormat().getOrElse(ContainerAttributes.ARCHIVE_FORMAT_OCI);
            var saveTask = project.getTasks().register(ContainerImage.saveTaskName(name), ContainerSaveTask.class, t -> {
                t.dependsOn(buildTask);
                t.getImage().convention(primaryTag);
                t.getFormat().convention(image.getArchiveFormat());
                t.getOutputFile().convention(
                        layout.getBuildDirectory().file(ContainerImage.archiveFilePath(name, format)));
            });
            var archiveElements = ContainerDependencies.archiveElements(project,
                    ContainerImage.archiveElementsName(name), name, format,
                    saveTask.flatMap(ContainerSaveTask::getOutputFile), saveTask);
            component.addVariantsFromConfiguration(archiveElements.get(), details -> { });
        }
    }
}
