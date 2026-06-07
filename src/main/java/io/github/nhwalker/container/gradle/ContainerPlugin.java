package io.github.nhwalker.container.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactSpec;
import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsAttributes;
import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies;
import io.github.nhwalker.container.gradle.dependency.ContainerAttributes;
import io.github.nhwalker.container.gradle.dsl.ContainerImage;
import io.github.nhwalker.container.gradle.tasks.AbstractContainerTask;
import io.github.nhwalker.container.gradle.tasks.ContainerBuildTask;
import io.github.nhwalker.container.gradle.tasks.ContainerImageReferenceTask;
import io.github.nhwalker.container.gradle.tasks.ContainerSaveTask;
import io.github.nhwalker.container.gradle.tasks.GenerateImageReferencesTask;

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

    /** The task that generates the {@code <ProjectName>Images} Java interface. */
    public static final String GENERATE_JAVA_REFS_TASK = "generateImageReferences";

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
        extension.getGenerateJavaRefs().convention(false);
        extension.getJavaRefsPackage().convention(
                project.provider(() -> String.valueOf(project.getGroup())));

        project.getTasks().withType(AbstractContainerTask.class).configureEach(task -> {
            task.setGroup(TASK_GROUP);
            task.getExecutable().convention(extension.getExecutable());
            task.getGlobalOptions().convention(extension.getGlobalOptions());
            task.getConnection().convention(extension.getConnection());
        });

        // Container images are modeled as generic artifacts: register the core
        // artifact schema, the container free-attribute keys, and the rule that
        // defaults an unrequested archive format to oci-archive.
        ArtifactsDependencies.registerSchema(project);
        ArtifactsDependencies.registerAttributeKey(project, ContainerAttributes.IMAGE_NAME_KEY);
        ArtifactsDependencies.registerAttributeKey(project, ContainerAttributes.IMAGE_TYPE_KEY);
        ArtifactsDependencies.registerAttributeKey(project, ContainerAttributes.ARCHIVE_FORMAT_KEY);
        project.getDependencies().getAttributesSchema()
                .attribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.ARCHIVE_FORMAT_KEY))
                .getDisambiguationRules().add(ContainerAttributes.ArchiveFormatDefaultRule.class);

        // One component aggregates every image's variants (one module/coordinate),
        // the same way the java component carries the main + sources/javadoc jars.
        AdhocComponentWithVariants component = softwareComponentFactory.adhoc(COMPONENT_NAME);
        project.getComponents().add(component);

        // Materialize each image's tasks/configs once the DSL is fully evaluated, so
        // structural decisions (e.g. whether an archive variant exists) see final values.
        project.afterEvaluate(p -> {
            List<TaskProvider<ContainerBuildTask>> buildTasks = new ArrayList<>();
            extension.getImages().forEach(image -> buildTasks.add(registerImage(p, image, component)));
            // When a Java plugin is applied and the user opted in, expose every image's
            // coordinate to Java code through a generated interface, refreshed on each build.
            if (extension.getGenerateJavaRefs().get() && p.getPluginManager().hasPlugin("java")) {
                registerJavaRefs(p, extension, buildTasks);
            }
        });
    }

    private void registerJavaRefs(Project project, ContainerExtension extension,
            List<TaskProvider<ContainerBuildTask>> buildTasks) {
        var generateTask = project.getTasks().register(
                GENERATE_JAVA_REFS_TASK, GenerateImageReferencesTask.class, t -> {
                    t.setGroup(TASK_GROUP);
                    t.setDescription("Generates the "
                            + GenerateImageReferencesTask.interfaceName(project.getName())
                            + " interface of built image references.");
                    t.getProjectName().convention(project.getName());
                    t.getPackageName().convention(extension.getJavaRefsPackage());
                    extension.getImages().forEach(image ->
                            t.getImages().put(image.getName(), image.getTags().map(tags -> tags.get(0))));
                    t.getOutputDirectory().convention(project.getLayout().getBuildDirectory()
                            .dir("generated/sources/containerImageRefs/java/main"));
                });

        // Compile the generated interface as part of the project's main sources.
        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        main.getJava().srcDir(generateTask.flatMap(GenerateImageReferencesTask::getOutputDirectory));

        // (Re)generate the interface whenever an image is built.
        buildTasks.forEach(buildTask -> buildTask.configure(t -> t.finalizedBy(generateTask)));

        // With the eclipse plugin, regenerating the classpath builds the images (which
        // refreshes the refs); depending on the generator too guarantees the generated
        // source folder exists before the .classpath that references it is written.
        project.getPluginManager().withPlugin("eclipse", applied ->
                project.getTasks().named("eclipseClasspath").configure(t -> {
                    t.dependsOn(generateTask);
                    buildTasks.forEach(t::dependsOn);
                }));
    }

    private TaskProvider<ContainerBuildTask> registerImage(Project project, ContainerImage image,
            AdhocComponentWithVariants component) {
        String name = image.getName();
        if (image.getTags().getOrElse(java.util.List.of()).isEmpty()) {
            throw new InvalidUserDataException(
                    "container image '" + name + "' must declare at least one tag");
        }
        ProjectLayout layout = project.getLayout();
        Provider<String> primaryTag = image.getTags().map(tags -> tags.get(0));

        TaskProvider<ContainerBuildTask> buildTask = project.getTasks().register(
                ContainerImage.buildTaskName(name), ContainerBuildTask.class, t -> {
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

        // Reference variant: classifier <image>-reference, free attrs imageName/imageType,
        // artifact type txt with the reference-writing task as its build dependency.
        var referenceElements = ArtifactsDependencies.elements(project,
                ContainerImage.referenceElementsName(name), name + "-reference",
                Map.of(ContainerAttributes.IMAGE_NAME_KEY, name,
                        ContainerAttributes.IMAGE_TYPE_KEY, ContainerAttributes.IMAGE_TYPE_REFERENCE),
                List.of(new ArtifactSpec(
                        referenceTask.flatMap(ContainerImageReferenceTask::getReferenceFile),
                        artifact -> {
                            artifact.setType("txt");
                            artifact.builtBy(referenceTask);
                        })));
        component.addVariantsFromConfiguration(referenceElements.get(), details -> { });

        if (image.getCreateArchive().get()) {
            String format = image.getArchiveFormat().getOrElse(ContainerAttributes.ARCHIVE_FORMAT_OCI);
            var saveTask = project.getTasks().register(ContainerImage.saveTaskName(name), ContainerSaveTask.class, t -> {
                t.dependsOn(buildTask);
                t.getImage().convention(primaryTag);
                t.getFormat().convention(image.getArchiveFormat());
                t.getOutputFile().convention(
                        layout.getBuildDirectory().file(ContainerImage.archiveFilePath(name, format)));
                // Pin archive freshness to the image's digest: when a digest is recorded the
                // reference file's content changes whenever the image content changes, so the
                // save re-runs only when the image actually changed instead of going stale
                // under a same-tag rebuild. (Reading the small file is cheap; re-saving is not.)
                if (image.getIncludeDigest().get()) {
                    t.getSourceReferenceFiles().from(
                            referenceTask.flatMap(ContainerImageReferenceTask::getReferenceFile));
                }
            });
            // Archive variant: classifier <image>, free attrs imageName/imageType/archiveFormat,
            // artifact type tar with the save task as its build dependency.
            var archiveElements = ArtifactsDependencies.elements(project,
                    ContainerImage.archiveElementsName(name), name,
                    Map.of(ContainerAttributes.IMAGE_NAME_KEY, name,
                            ContainerAttributes.IMAGE_TYPE_KEY, ContainerAttributes.IMAGE_TYPE_ARCHIVE,
                            ContainerAttributes.ARCHIVE_FORMAT_KEY, format),
                    List.of(new ArtifactSpec(
                            saveTask.flatMap(ContainerSaveTask::getOutputFile),
                            artifact -> {
                                artifact.setType("tar");
                                artifact.builtBy(saveTask);
                            })));
            component.addVariantsFromConfiguration(archiveElements.get(), details -> { });
        }
        return buildTask;
    }
}
