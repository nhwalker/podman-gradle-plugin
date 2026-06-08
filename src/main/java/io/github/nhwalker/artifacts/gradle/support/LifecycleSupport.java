package io.github.nhwalker.artifacts.gradle.support;

import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * Shared helpers for tying the container, helm, and generic-artifacts plugins' tasks into the standard
 * lifecycle tasks contributed by {@link LifecycleBasePlugin} ({@code assemble}/{@code check}/{@code
 * build}/{@code clean}). Each plugin applies {@code LifecycleBasePlugin} so these tasks exist, then uses
 * {@link #assembleDependsOn}/{@link #checkDependsOn} to attach its production/verification tasks.
 *
 * <p>Lifecycle participation is a tri-state: a project-wide default (the extension's
 * {@code lifecycleIntegration}, defaulting to {@code true}) that each declared item can override either
 * way through its own unset-by-default {@code lifecycleIntegration} flag — see {@link #enabled}.
 */
public final class LifecycleSupport {

    private LifecycleSupport() {
    }

    /**
     * Resolves whether an item participates in the lifecycle: the item's own flag when set, otherwise
     * the project-wide default. {@code projectDefault} is the extension's {@code lifecycleIntegration}
     * provider (conventionally {@code true}).
     */
    public static boolean enabled(Property<Boolean> itemFlag, Provider<Boolean> projectDefault) {
        return itemFlag.getOrElse(projectDefault.get());
    }

    /** Makes the {@code assemble} lifecycle task depend on the given task(s)/buildable(s). */
    public static void assembleDependsOn(Project project, Object... tasks) {
        project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)
                .configure(task -> task.dependsOn(tasks));
    }

    /** Makes the {@code check} lifecycle task depend on the given task(s)/buildable(s). */
    public static void checkDependsOn(Project project, Object... tasks) {
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME)
                .configure(task -> task.dependsOn(tasks));
    }
}
