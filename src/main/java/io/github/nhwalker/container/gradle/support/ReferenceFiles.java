package io.github.nhwalker.container.gradle.support;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads the single-line image reference files written by
 * {@link io.github.nhwalker.container.gradle.tasks.ContainerImageReferenceTask}. The contract,
 * shared by every reader: the first non-blank line is the image coordinate — {@code name:tag},
 * digest-pinned as {@code name:tag@sha256:…} when a digest was recorded.
 */
public final class ReferenceFiles {

    private ReferenceFiles() {
    }

    /**
     * The first non-blank line of {@code file}, stripped, or {@code null} when the file contains
     * none. Fails when the file cannot be read (including when it does not exist); use
     * {@link #readFirstLineIfPresent(File)} to treat a missing file as no reference.
     */
    public static String readFirstLine(File file) {
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read image reference file " + file, e);
        }
    }

    /** Like {@link #readFirstLine(File)}, but {@code null} when {@code file} is not a regular file. */
    public static String readFirstLineIfPresent(File file) {
        return file.isFile() ? readFirstLine(file) : null;
    }
}
