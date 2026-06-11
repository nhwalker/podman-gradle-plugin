package io.github.nhwalker.artifacts.gradle.support;

/**
 * The inputs of a staging {@code Sync} task: what the task {@code dependsOn} and what it copies
 * {@code from}. Usually one file collection plays both roles — {@link #of(Object)} — but a task
 * can also depend on one thing while copying another: unpack tasks depend on the resolved
 * archives but copy from the expanded archive trees (which carry no build dependencies of their
 * own), and the helm bundle task depends on the package task by name while copying its output
 * file — {@link #of(Object, Object)}.
 */
public final class StagingSources {

    private final Object taskDependencies;
    private final Object copySource;

    private StagingSources(Object taskDependencies, Object copySource) {
        this.taskDependencies = taskDependencies;
        this.copySource = copySource;
    }

    /** Sources whose copied files carry the build dependencies themselves. */
    public static StagingSources of(Object dependenciesAndSource) {
        return new StagingSources(dependenciesAndSource, dependenciesAndSource);
    }

    /** Sources whose {@code copySource} does not carry the build dependencies on its own. */
    public static StagingSources of(Object taskDependencies, Object copySource) {
        return new StagingSources(taskDependencies, copySource);
    }

    /** What the staging task {@code dependsOn} (any task-dependency notation). */
    public Object getTaskDependencies() {
        return taskDependencies;
    }

    /** What the staging task copies {@code from} (any {@code CopySpec.from} notation). */
    public Object getCopySource() {
        return copySource;
    }
}
