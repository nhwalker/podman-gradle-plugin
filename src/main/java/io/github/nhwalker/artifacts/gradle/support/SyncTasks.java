package io.github.nhwalker.artifacts.gradle.support;

import org.gradle.api.Action;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * The shared register-if-absent primitive behind the DSL's idempotent {@code Sync}-task handles
 * ({@code downloadTask()}/{@code unpackTask()}/{@code importResourcesTask()}), which may be called
 * repeatedly — including from inside another task's configuration block, where registering twice
 * would fail.
 *
 * <p>Two configuration conventions are built on top of this primitive, and they deliberately
 * differ — be aware which one a caller implements:
 * <ul>
 *   <li><strong>staging tasks</strong> ({@code downloadTask}/{@code unpackTask}) re-apply a
 *       non-null user configuration on every call, because it configures the {@code Sync} task
 *       itself and task configuration actions are additive; and</li>
 *   <li><strong>resource imports</strong> ({@code importResourcesTask}, via
 *       {@link ResourceImports#register}) apply the user configuration only on the first call,
 *       because it configures the child copy spec created by {@code from(source, configuration)},
 *       which cannot be re-entered once created.</li>
 * </ul>
 */
public final class SyncTasks {

    private SyncTasks() {
    }

    /**
     * Registers a {@code Sync} task with {@code initialConfiguration} when {@code taskName} is not
     * yet registered, otherwise returns the existing task untouched (the initial configuration is
     * <em>not</em> re-applied).
     */
    public static TaskProvider<Sync> registerIfAbsent(TaskContainer tasks, String taskName,
            Action<? super Sync> initialConfiguration) {
        if (tasks.getNames().contains(taskName)) {
            return tasks.named(taskName, Sync.class);
        }
        return tasks.register(taskName, Sync.class, initialConfiguration);
    }
}
