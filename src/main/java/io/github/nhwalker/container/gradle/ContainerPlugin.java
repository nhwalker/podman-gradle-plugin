package io.github.nhwalker.container.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactSpec;
import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsAttributes;
import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies;
import io.github.nhwalker.artifacts.gradle.support.LifecycleSupport;
import io.github.nhwalker.artifacts.gradle.support.PublishingSupport;
import io.github.nhwalker.artifacts.gradle.support.ResourceImports;
import io.github.nhwalker.container.gradle.dependency.ContainerAttributes;
import io.github.nhwalker.container.gradle.dsl.ContainerArchive;
import io.github.nhwalker.container.gradle.dsl.ContainerImage;
import io.github.nhwalker.container.gradle.tasks.AbstractContainerTask;
import io.github.nhwalker.container.gradle.tasks.ContainerArchiveTask;
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

    /** The task that generates the {@code main} source set's {@code <ProjectName>Images} Java interface. */
    public static final String GENERATE_REFERENCES_TASK = "generateImageReferences";

    /** The {@code <Domain>} segment of this plugin's generated interface name. */
    public static final String REFERENCES_DOMAIN = "Images";

    /**
     * The references task name for a source set: {@link #GENERATE_REFERENCES_TASK} for {@code main},
     * {@code generate<SourceSet>ImageReferences} otherwise.
     */
    public static String generateReferencesTaskName(String sourceSetName) {
        if (SourceSet.MAIN_SOURCE_SET_NAME.equals(sourceSetName)) {
            return GENERATE_REFERENCES_TASK;
        }
        return "generate" + Character.toUpperCase(sourceSetName.charAt(0)) + sourceSetName.substring(1)
                + "ImageReferences";
    }

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
        extension.getReferencesPackage().convention(
                project.provider(() -> String.valueOf(project.getGroup())));
        extension.getReferencesClassName().convention(project.provider(() ->
                ResourceImports.defaultReferencesBaseName(project.getName(), REFERENCES_DOMAIN)));
        extension.getLifecycleIntegration().convention(true);

        // Contribute assemble/check/build/clean so images can be wired into the standard build,
        // even in a project that does not also apply the java/base plugin (idempotent if it does).
        project.getPluginManager().apply(org.gradle.language.base.plugins.LifecycleBasePlugin.class);

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

        // One shared component aggregates every image's variants (one module/coordinate), the same way
        // the java component carries the main + sources/javadoc jars. Created eagerly so
        // `components.genericArtifacts` is resolvable in the publishing block.
        PublishingSupport.registerComponent(project, softwareComponentFactory);

        // Materialize each image's tasks/configs once the DSL is fully evaluated, so
        // structural decisions (e.g. whether an archive variant exists) see final values.
        project.afterEvaluate(p -> {
            Map<String, TaskProvider<ContainerImageReferenceTask>> referenceTasks = new LinkedHashMap<>();
            extension.getImages().forEach(image ->
                    referenceTasks.put(image.getName(), registerImage(p, extension, image)));
            // Multi-image archives are materialized after images so a bundle can reference sibling
            // images (their reference tasks/configs already exist) and name collisions are caught.
            extension.getArchives().forEach(archive -> registerArchive(p, extension, archive));
            // When a Java plugin is applied, expose each opted-in image's reference to Java code
            // through a generated interface (per target source set). No-ops when nothing opted in.
            if (p.getPluginManager().hasPlugin("java")) {
                registerReferences(p, extension, referenceTasks);
            }
        });
    }

    private void registerArchive(Project project, ContainerExtension extension, ContainerArchive archive) {
        String name = archive.getName();
        boolean lifecycle = LifecycleSupport.enabled(
                archive.getLifecycleIntegration(), extension.getLifecycleIntegration());
        String classifier = archive.getClassifier().get();
        // A bundle publishes an imageType=archive variant; a name/classifier shared with an image (whose
        // own archive uses classifier <image>) would publish a duplicate variant. Fail fast instead.
        extension.getImages().forEach(image -> {
            if (image.getName().equals(name) || image.getName().equals(classifier)) {
                throw new InvalidUserDataException("container archive '" + name + "' (classifier '"
                        + classifier + "') collides with image '" + image.getName()
                        + "'; give the archive a distinct name or classifier");
            }
        });

        boolean defaultArtifact = archive.getDefaultArtifact().get();
        String format = archive.getFormat().getOrElse(ContainerAttributes.ARCHIVE_FORMAT_OCI);
        TaskProvider<ContainerArchiveTask> saveTask = project.getTasks().register(
                ContainerArchive.saveTaskName(name), ContainerArchiveTask.class, t -> {
                    t.getImageReferenceFiles().from(archive.getImageReferenceFiles());
                    t.getImageStrings().convention(archive.getImageStrings());
                    t.getFormat().convention(archive.getFormat());
                    t.getPullPolicy().convention(archive.getPullPolicy());
                    t.getOutputFile().convention(project.getLayout().getBuildDirectory()
                            .file(ContainerArchive.archiveFilePath(name, format)));
                });

        // Archive variant: classifier <archive> (or unclassified when default), free attrs
        // imageName/imageType/archiveFormat, artifact type tar with the save task as its build dependency.
        var archiveElements = ArtifactsDependencies.elements(project,
                ContainerArchive.archiveBundleElementsName(name), classifier,
                Map.of(ContainerAttributes.IMAGE_NAME_KEY, name,
                        ContainerAttributes.IMAGE_TYPE_KEY, ContainerAttributes.IMAGE_TYPE_ARCHIVE,
                        ContainerAttributes.ARCHIVE_FORMAT_KEY, format),
                List.of(new ArtifactSpec(
                        saveTask.flatMap(ContainerArchiveTask::getOutputFile),
                        artifact -> {
                            artifact.setType("tar");
                            artifact.builtBy(saveTask);
                        })),
                defaultArtifact);
        PublishingSupport.addVariants(project, softwareComponentFactory, archiveElements.get(),
                defaultArtifact ? "container archive '" + name + "'" : null);
        if (lifecycle) {
            LifecycleSupport.assembleDependsOn(project, saveTask);
        }
    }

    private void registerReferences(Project project, ContainerExtension extension,
            Map<String, TaskProvider<ContainerImageReferenceTask>> referenceTasks) {
        // Group each opted-in image's constant under the source set(s) it targets, so each source set
        // gets its own <ProjectName>Images[<SourceSet>] interface. The value is the image's resolved
        // reference (digest-pinned when includeDigest is on), read from its reference file — so the
        // interface's source set is what builds and inspects the image, scoping that dependency.
        Map<String, Map<String, Provider<String>>> constantsBySourceSet = new LinkedHashMap<>();
        Map<String, List<TaskProvider<? extends Task>>> regenerateBySourceSet = new LinkedHashMap<>();
        extension.getImages().forEach(image -> {
            if (image.getJavaReferenceSourceSets().isEmpty()) {
                return;
            }
            TaskProvider<ContainerImageReferenceTask> referenceTask = referenceTasks.get(image.getName());
            Provider<String> value = referenceTask
                    .flatMap(ContainerImageReferenceTask::getReferenceFile)
                    .map(ContainerPlugin::readReference);
            image.getJavaReferenceSourceSets().forEach(sourceSet -> {
                constantsBySourceSet.computeIfAbsent(sourceSet, k -> new LinkedHashMap<>())
                        .put(image.getName(), value);
                regenerateBySourceSet.computeIfAbsent(sourceSet, k -> new ArrayList<>()).add(referenceTask);
            });
        });
        constantsBySourceSet.forEach((sourceSet, constants) -> {
            String className = ResourceImports.withSourceSetSuffix(
                    extension.getReferencesClassName().get(), sourceSet);
            Provider<Directory> output = project.getLayout().getBuildDirectory()
                    .dir("generated/sources/containerImageRefs/java/" + sourceSet);
            // Depending on the reference tasks (via the helper) makes generating the interface — or the
            // eclipse classpath — build and inspect the images first, so the refs reflect the current
            // image content and are present for the IDE.
            ResourceImports.generateReferences(project, TASK_GROUP, generateReferencesTaskName(sourceSet),
                    className, extension.getReferencesPackage(),
                    "Generated by the io.github.nhwalker.container plugin. Do not edit.",
                    constants, output, sourceSet, regenerateBySourceSet.get(sourceSet));
        });
    }

    /**
     * Reads the first non-blank line of an image's reference file (the coordinate, digest-pinned when
     * available). A {@code static} method so the value provider captures no project state and stays
     * configuration-cache safe.
     */
    private static String readReference(RegularFile file) {
        Path path = file.getAsFile().toPath();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read image reference file " + path, e);
        }
    }

    /**
     * Validates and returns the image's {@code defaultArtifact} selector ({@code archive}/{@code
     * reference}), or {@code null} when none is designated. Rejects unknown values and {@code archive}
     * without {@code createArchive}.
     */
    private static String resolveDefaultArtifact(ContainerImage image, boolean createArchive) {
        String selector = image.getDefaultArtifact().getOrNull();
        if (selector == null) {
            return null;
        }
        if (!ContainerImage.DEFAULT_ARTIFACT_ARCHIVE.equals(selector)
                && !ContainerImage.DEFAULT_ARTIFACT_REFERENCE.equals(selector)) {
            throw new InvalidUserDataException("container image '" + image.getName()
                    + "' defaultArtifact must be '" + ContainerImage.DEFAULT_ARTIFACT_ARCHIVE + "' or '"
                    + ContainerImage.DEFAULT_ARTIFACT_REFERENCE + "', but was '" + selector + "'");
        }
        if (ContainerImage.DEFAULT_ARTIFACT_ARCHIVE.equals(selector) && !createArchive) {
            throw new InvalidUserDataException("container image '" + image.getName()
                    + "' defaultArtifact '" + ContainerImage.DEFAULT_ARTIFACT_ARCHIVE
                    + "' requires createArchive = true");
        }
        return selector;
    }

    private TaskProvider<ContainerImageReferenceTask> registerImage(Project project,
            ContainerExtension extension, ContainerImage image) {
        String name = image.getName();
        boolean lifecycle = LifecycleSupport.enabled(
                image.getLifecycleIntegration(), extension.getLifecycleIntegration());
        if (image.getTags().getOrElse(java.util.List.of()).isEmpty()) {
            throw new InvalidUserDataException(
                    "container image '" + name + "' must declare at least one tag");
        }
        boolean createArchive = image.getCreateArchive().get();
        String defaultArtifact = resolveDefaultArtifact(image, createArchive);
        boolean referenceIsDefault = ContainerImage.DEFAULT_ARTIFACT_REFERENCE.equals(defaultArtifact);
        boolean archiveIsDefault = ContainerImage.DEFAULT_ARTIFACT_ARCHIVE.equals(defaultArtifact);
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

        // Default-on: building the image is part of `assemble` (and so `build`). Opt out per project
        // (extension) or per image (image.lifecycleIntegration). The archive, when created, is wired
        // below so `assemble` also produces it.
        if (lifecycle) {
            LifecycleSupport.assembleDependsOn(project, buildTask);
        }

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
                        })),
                referenceIsDefault);
        PublishingSupport.addVariants(project, softwareComponentFactory, referenceElements.get(),
                referenceIsDefault ? "container image '" + name + "' reference" : null);

        if (createArchive) {
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
                            })),
                    archiveIsDefault);
            PublishingSupport.addVariants(project, softwareComponentFactory, archiveElements.get(),
                    archiveIsDefault ? "container image '" + name + "' archive" : null);
            if (lifecycle) {
                LifecycleSupport.assembleDependsOn(project, saveTask);
            }
        }
        return referenceTask;
    }
}
