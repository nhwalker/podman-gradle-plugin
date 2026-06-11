package io.github.nhwalker.artifacts.gradle.support;

import org.gradle.api.provider.Property;

/**
 * The configuration surface the artifacts, container, and helm extensions share: where the
 * generated references interface lives ({@link #getReferencesPackage() package} and
 * {@link #getReferencesClassName() class name}) and whether declared elements participate in the
 * standard lifecycle tasks by default ({@link #getLifecycleIntegration()}). Implementing this
 * interface lets the common convention-setting and reference-generation wiring live once in
 * {@link ResourceImports} instead of being repeated per plugin; each extension documents the
 * plugin-specific meaning on its own overrides.
 */
public interface ReferencesExtension {

    /** The package the generated references interface(s) are placed into. */
    Property<String> getReferencesPackage();

    /** The name of the generated references interface for the {@code main} source set. */
    Property<String> getReferencesClassName();

    /** Whether declared elements participate in the standard lifecycle tasks by default. */
    Property<Boolean> getLifecycleIntegration();
}
