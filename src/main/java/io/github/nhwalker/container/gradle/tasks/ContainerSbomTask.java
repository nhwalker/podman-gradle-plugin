package io.github.nhwalker.container.gradle.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import io.github.nhwalker.container.gradle.dependency.ContainerAttributes;

/**
 * Generates a Software Bill of Materials (SBOM) for a built image by running
 * <a href="https://github.com/anchore/syft">Anchore Syft</a> inside a container.
 *
 * <p>Rather than requiring a {@code syft} binary on the host, this task runs the Syft
 * image via {@code podman run}, mounting the image's saved tar archive read-only and
 * pointing Syft at it as an {@code oci-archive:}/{@code docker-archive:} source:
 * <pre>
 * podman run --rm --pull missing \
 *     -v &lt;archive.tar&gt;:/scan/image.tar:ro &lt;syftImage&gt; \
 *     scan oci-archive:/scan/image.tar -o cyclonedx-json
 * </pre>
 * Syft writes the SBOM to standard output (its logs go to standard error), so the
 * document is captured from stdout and written to {@link #getSbomFile()} by the Gradle
 * process itself. Scanning the tar (a real file input) rather than podman storage keeps
 * the up-to-date check fully file-based, and capturing stdout instead of mounting an
 * output directory avoids the rootless-podman file-ownership pitfalls of container-written
 * files.
 *
 * <p>This task should depend on the {@link ContainerSaveTask} that produces the archive.
 */
public abstract class ContainerSbomTask extends AbstractContainerTask {

    /** The mount target inside the Syft container for the scanned archive. */
    private static final String CONTAINER_TAR_PATH = "/scan/image.tar";

    /** The saved image archive Syft scans. Required. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchiveFile();

    /**
     * The archive format of {@link #getArchiveFile()} ({@code oci-archive} or
     * {@code docker-archive}); selects the Syft source scheme. Defaults to {@code oci-archive}.
     */
    @Input
    public abstract Property<String> getArchiveFormat();

    /** The Syft container image to run, for example {@code docker.io/anchore/syft:v1.18.1}. Required. */
    @Input
    public abstract Property<String> getSyftImage();

    /**
     * The {@code podman run --pull} policy for the Syft image ({@code missing}, {@code always},
     * {@code never} or {@code newer}). Defaults to {@code missing}.
     */
    @Input
    public abstract Property<String> getSyftPullPolicy();

    /** The Syft output format ({@code -o}). Defaults to {@code cyclonedx-json}. */
    @Input
    public abstract Property<String> getSbomFormat();

    /** The SBOM document to write. */
    @OutputFile
    public abstract RegularFileProperty getSbomFile();

    @Override
    protected List<String> buildSubcommand() {
        String tar = getArchiveFile().get().getAsFile().getAbsolutePath();
        String scheme = sourceScheme(getArchiveFormat().getOrElse(ContainerAttributes.ARCHIVE_FORMAT_OCI));
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("--rm");
        addOption(args, "--pull", getSyftPullPolicy().getOrNull());
        args.add("-v");
        args.add(tar + ":" + CONTAINER_TAR_PATH + ":ro");
        args.add(getSyftImage().get());
        args.add("scan");
        args.add(scheme + ":" + CONTAINER_TAR_PATH);
        args.add("-o");
        args.add(getSbomFormat().getOrElse(ContainerAttributes.SBOM_FORMAT_CYCLONEDX));
        return args;
    }

    @Override
    public void execute() {
        Path target = getSbomFile().get().getAsFile().toPath();
        if (getDryRun().get()) {
            getLogger().lifecycle("[dry-run] {}", String.join(" ", assembleCommand()));
            return;
        }
        String sbom = runSubcommand(buildSubcommand(), true);
        if (sbom == null || sbom.isBlank()) {
            throw new GradleException("Syft produced an empty SBOM for " + target
                    + "; check that " + getSyftImage().get() + " ran successfully");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, sbom, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write SBOM file " + target, e);
        }
    }

    /** Maps an archive format to the Syft source scheme, mirroring {@code ContainerImage.archiveFilePath}. */
    private static String sourceScheme(String archiveFormat) {
        return archiveFormat != null && archiveFormat.startsWith("docker")
                ? ContainerAttributes.ARCHIVE_FORMAT_DOCKER : ContainerAttributes.ARCHIVE_FORMAT_OCI;
    }
}
