package io.github.nhwalker.artifacts.gradle.support;

import org.gradle.api.tasks.SourceSet;

/**
 * Shared naming helpers behind the task/configuration/type names the artifacts, container, and
 * helm plugins derive from element and source-set names. Kept in one place so the conventions
 * ("task names embed the capitalized element name", "{@code main} is unqualified") have a single
 * home instead of per-class private copies.
 */
public final class Names {

    private Names() {
    }

    /** Uppercases the first character ({@code app} &rarr; {@code App}); null/empty returned as-is. */
    public static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * The name segment qualifying a source set: empty for the conventional {@code main} source set
     * (so its names stay unqualified), otherwise the capitalized source-set name.
     */
    public static String sourceSetQualifier(String sourceSetName) {
        return SourceSet.MAIN_SOURCE_SET_NAME.equals(sourceSetName) ? "" : capitalize(sourceSetName);
    }
}
