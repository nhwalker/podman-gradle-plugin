# podman-gradle-plugin

A standalone Gradle plugin that contributes tasks for working with
[Podman](https://podman.io/) in a Gradle build.

This README explains, in detail, **how the plugin works** — its design, its
internal architecture, how a podman command line is assembled and executed, and
how each task maps to a podman subcommand. For a quick start, jump to
[Usage](#usage).

- **Implementation language:** Java
- **Build scripts:** Groovy
- **Built and tested with:** Gradle 9.2, Java 17+
- **Plugin id:** `io.github.nhwalker.container`

This build also ships a sibling **Helm** plugin (`io.github.nhwalker.helm`) that
follows the same design for the `helm` CLI — see [Helm Plugin](#helm-plugin).

---

## Design philosophy

The plugin is built around three deliberate choices.

1. **It drives the `podman` command line, not an API.** Every task ultimately
   runs the real `podman` executable as a child process. There is no Docker
   socket, no REST client, no library binding. If a command works in your shell,
   it works here, and the plugin automatically inherits whatever podman
   configuration (machine, rootless setup, registries, auth) your environment
   already has. Execution goes through Gradle's
   [`ExecOperations`](https://docs.gradle.org/current/javadoc/org/gradle/process/ExecOperations.html)
   service rather than a raw `ProcessBuilder`, so the plugin integrates cleanly
   with Gradle's process handling and the configuration cache.

2. **Everything is lazy.** All task inputs are exposed as Gradle
   [`Property`/`Provider`](https://docs.gradle.org/current/userguide/lazy_configuration.html)
   types. Nothing is read at configuration time; the command line is materialized
   only when the task actually runs. This keeps the plugin compatible with
   configuration-on-demand, lazy task realization, and the configuration cache.

3. **It contributes task *types*, not tasks.** Applying the plugin does not add
   any tasks to your build. It registers one extension and a family of task
   classes. You declare exactly the tasks you need. This avoids polluting the
   task graph and lets you name and wire tasks to fit your build.

---

## Architecture

The plugin has four kinds of moving part:

```
ContainerPlugin                 ← entry point; registers extension + wires conventions
   │
   ├── ContainerExtension       ← the `container { }` block (shared defaults)
   │
   └── AbstractContainerTask    ← the engine: assembles + executes the command
          ├── ContainerBuildTask
          ├── ContainerPushTask
          ├── ContainerPullTask
          ├── ContainerTagTask
          ├── ContainerRunTask
          ├── ContainerStopTask
          ├── ContainerRemoveContainerTask
          ├── ContainerRemoveImageTask
          ├── ContainerSaveTask
          ├── ContainerLoadTask
          ├── ContainerCopyFromImageTask  ← create + cp + rm orchestration
          └── ContainerExecTask  ← generic escape hatch
```

### 1. `ContainerPlugin` — the entry point

When you apply `id 'io.github.nhwalker.container'`, the plugin's `apply(Project)`
does two small things:

```java
public void apply(Project project) {
    // (a) register the `container { }` extension and give `executable` a default
    ContainerExtension extension =
            project.getExtensions().create("container", ContainerExtension.class);
    extension.getExecutable().convention("podman");

    // (b) for EVERY container task that ever gets created, inherit the extension's
    //     values as conventions and put the task in the "container" group
    project.getTasks().withType(AbstractContainerTask.class).configureEach(task -> {
        task.setGroup("container");
        task.getExecutable().convention(extension.getExecutable());
        task.getGlobalOptions().convention(extension.getGlobalOptions());
        task.getConnection().convention(extension.getConnection());
    });
}
```

The key mechanism is `tasks.withType(...).configureEach(...)`. This is a *live*,
lazy hook: it runs its configuration block for any current **or future** task of
type `AbstractContainerTask`, without forcing those tasks to be created. That is how
a value you set once in `container { }` reaches every container task — even tasks
registered after the `container { }` block — while staying compatible with lazy task
realization.

Because these are `convention(...)` calls (not `set(...)`), they only supply a
*default*. Anything you set explicitly on an individual task overrides the
extension.

### 2. `ContainerExtension` — shared defaults

The extension is an abstract class with three lazy properties, so Gradle
generates the implementation:

| Property        | Type                   | Meaning                                                            |
|-----------------|------------------------|--------------------------------------------------------------------|
| `executable`    | `Property<String>`     | Which binary to run. Defaults to `podman` (resolved on `PATH`).    |
| `globalOptions` | `ListProperty<String>` | Flags inserted *before* the subcommand on every call. Empty by default. |
| `connection`    | `Property<String>`     | Optional `--connection <name>` for a remote podman service.        |

These exist purely to be wired into tasks as conventions (see above).

### 3. `AbstractContainerTask` — the engine

This is where the real work happens. Every concrete task extends it. It owns the
shared inputs, the command-assembly algorithm, and the execution logic.

**Shared inputs** (all lazy):

| Property          | Annotation         | Default  | Effect                                                        |
|-------------------|--------------------|----------|---------------------------------------------------------------|
| `executable`      | `@Input`           | `podman` | The binary to invoke.                                         |
| `globalOptions`   | `@Input`           | `[]`     | Flags placed between the executable and the subcommand.       |
| `connection`      | `@Input @Optional` | unset    | Adds `--connection <name>` when present.                      |
| `ignoreExitValue` | `@Input`           | `false`  | If `true`, a non-zero exit does not fail the task.            |
| `dryRun`          | `@Input`           | `false`  | If `true`, log the command and **skip** execution.            |
| `captureOutput`   | `@Internal`        | `false`  | If `true`, capture stdout instead of streaming it.            |

`ExecOperations` is obtained by constructor injection:

```java
@Inject
protected abstract ExecOperations getExecOperations();
```

Injecting the service (rather than calling `project.exec { }`) is what keeps the
task safe to execute under the configuration cache — the task never captures a
reference to the `Project` at execution time.

#### Command assembly

Every concrete task implements one method:

```java
protected abstract List<String> buildSubcommand();
```

It returns the podman subcommand and its arguments, e.g.
`["build", "-t", "img:latest", "."]`. The base class wraps that into a full
command line with `assembleCommand()`:

```
[ executable ] + [ globalOptions… ] + [ "--connection", name ]? + [ buildSubcommand()… ]
```

Concretely:

```java
List<String> command = new ArrayList<>();
command.add(getExecutable().get());          // e.g. "podman"
command.addAll(getGlobalOptions().get());    // e.g. "--log-level", "info"
if (getConnection().isPresent()) {           // optional
    command.add("--connection");
    command.add(getConnection().get());
}
command.addAll(buildSubcommand());           // the task-specific part
```

To keep the subcommand builders terse and consistent, the base class provides
three static helpers that all tasks use:

- `addOption(args, "--flag", value)` — appends `--flag value` **only if** `value`
  is non-null and non-blank.
- `addFlag(args, "--flag", enabled)` — appends `--flag` once **only if**
  `enabled` is true.
- `addRepeated(args, "-p", values)` — appends `-p value` for **each** entry in a
  collection (used for ports, volumes, etc.).

This is why unset optional properties simply vanish from the command line instead
of producing empty or malformed arguments.

#### Execution

The `@TaskAction` method ties it together:

```java
@TaskAction
public void execute() {
    List<String> command = assembleCommand();

    if (getDryRun().get()) {                       // 1. dry-run short-circuit
        getLogger().lifecycle("[dry-run] {}", String.join(" ", command));
        return;
    }

    getLogger().info("Executing: {}", String.join(" ", command));

    var buffer = getCaptureOutput().get()          // 2. optional stdout capture
            ? new ByteArrayOutputStream() : null;

    ExecResult result = getExecOperations().exec(spec -> {   // 3. run podman
        spec.commandLine(command);
        spec.setIgnoreExitValue(getIgnoreExitValue().get());
        if (buffer != null) spec.setStandardOutput(buffer);
    });

    if (buffer != null)                            // 4. stash captured output
        capturedStandardOutput = buffer.toString(StandardCharsets.UTF_8);

    if (getIgnoreExitValue().get())                // 5. exit handling
        getLogger().info("{} exited with code {}", getExecutable().get(),
                         result.getExitValue());
    else
        result.assertNormalExitValue();
}
```

The five steps:

1. **Dry run** — when enabled, the assembled command is printed at lifecycle
   level and nothing is executed. Useful for inspecting exactly what the plugin
   would run.
2. **Capture** — when `captureOutput` is on, stdout is redirected to an in-memory
   buffer; otherwise podman's stdout/stderr stream straight to the Gradle
   console (the default `ExecOperations` behavior).
3. **Run** — `ExecOperations.exec` forks the process and waits for it. By default
   a non-zero exit throws.
4. **Stash** — captured output is decoded as UTF-8 and exposed via
   `task.getStandardOutput()` for `doLast { }` blocks to consume.
5. **Exit handling** — with `ignoreExitValue = true`, the exit code is merely
   logged so the build continues (handy for idempotent cleanup like "stop a
   container that may not exist"); otherwise `assertNormalExitValue()` fails the
   task on any non-zero code.

### 4. Concrete tasks

Each concrete task is small: it declares its own typed properties and implements
`buildSubcommand()` by translating those properties into podman arguments using
the helpers. For example, `ContainerBuildTask.buildSubcommand()` does roughly:

```java
List<String> args = new ArrayList<>();
args.add("build");
for (String tag : getTags().get())      addOption(args, "-t", tag);
if (getContainerfile().isPresent())     addOption(args, "-f", <abs path>);
for (var e : getBuildArgs().get())      addOption(args, "--build-arg", e.key + "=" + e.value);
for (var e : getLabels().get())         addOption(args, "--label", e.key + "=" + e.value);
addOption(args, "--platform", getPlatform().getOrNull());
addOption(args, "--target",   getTarget().getOrNull());
addFlag(args, "--no-cache", getNoCache().get());
addFlag(args, "--pull",     getPull().get());
args.addAll(getExtraArguments().get());
args.add(<context dir abs path>);       // context goes last, as podman expects
```

Every task also exposes an `extraArguments` (or `arguments`) list so you can pass
podman flags the typed API doesn't model yet — the plugin never blocks you from a
podman feature.

---

## How each task maps to podman

All task types live in the package
`io.github.nhwalker.container.gradle.tasks`.

| Task type                    | Command   | Notable typed properties                                                                 |
|------------------------------|-----------|------------------------------------------------------------------------------------------|
| `ContainerBuildTask`            | `build`   | `contextDirectory`, `containerfile`, `tags`, `buildArgs`, `labels`, `platform`, `target`, `noCache`, `pull` |
| `ContainerPushTask`             | `push`    | `image`, `destination`, `tlsVerify`                                                       |
| `ContainerPullTask`             | `pull`    | `image`, `platform`, `tlsVerify`                                                          |
| `ContainerTagTask`              | `tag`     | `sourceImage`, `targetImages` (runs once per target — see below)                         |
| `ContainerRunTask`              | `run`     | `image`, `containerName`, `detach`, `remove`, `tty`, `interactive`, `ports`, `volumes`, `environment`, `command` |
| `ContainerStopTask`             | `stop`    | `containers`, `all`, `stopTimeout`                                                        |
| `ContainerRemoveContainerTask`  | `rm`      | `containers`, `force`, `volumes`, `all`                                                   |
| `ContainerRemoveImageTask`      | `rmi`     | `images`, `force`, `all`                                                                  |
| `ContainerSaveTask`             | `save`    | `image`, `outputFile`, `format`                                                           |
| `ContainerLoadTask`             | `load`    | `inputFile`                                                                               |
| `ContainerCopyFromImageTask`    | `create` + `cp` + `rm` | `image` *or* `container`, `paths`, `createOptions`, `copyOptions`, `removeContainer` |
| `ContainerExecTask`             | *any*     | `arguments`                                                                               |

### Tasks that issue more than one podman command

Most tasks run exactly one podman invocation. Two orchestrate several, building
on the shared `runSubcommand(...)` primitive in `AbstractContainerTask` (which still
honors `dryRun`/`ignoreExitValue` for every call):

- **`ContainerTagTask`** — `podman tag` only accepts a single new name per
  invocation, so the task runs `podman tag <source> <target>` **once per entry**
  in `targetImages`.

- **`ContainerCopyFromImageTask`** — `podman cp` operates on *containers*, not
  images. To extract files from an image the task:
  1. runs `podman create <image>` and captures the new container id from stdout
     (the container is created but never started);
  2. runs `podman cp <container>:<source> <destination>` for **each** entry in
     `paths`, creating each destination's parent directory first;
  3. runs `podman rm -f <container>` in a `finally` block so the temporary
     container is always cleaned up, even if a copy fails. (Cleanup ignores its
     own exit code so it can never mask the real error.)

  If you already have a container, set `container` instead of `image` and the
  task skips the create/remove steps and copies straight from it.

---

## Incremental builds & up-to-date behavior

Most podman actions mutate state that lives in podman's own storage, not on the
filesystem, so Gradle cannot meaningfully cache them. The plugin reflects this
honestly through its input/output annotations:

- **Action tasks declare no outputs** (`build`, `push`, `pull`, `run`, `stop`,
  `rm`, `rmi`, `tag`). A task with no declared outputs is never considered
  up-to-date, so these run every time you invoke them — which is the correct
  behavior for imperative, side-effecting commands.
- **`ContainerBuildTask.contextDirectory` is `@Internal`**, not an input. The build
  context can be huge (and podman/BuildKit do their own change detection), so the
  plugin deliberately does not snapshot it.
- **`ContainerSaveTask` declares its archive as `@OutputFile`** and `ContainerLoadTask`
  declares its `@InputFile`. These produce/consume real files, so they *do*
  participate in up-to-date checks and can be skipped when nothing changed. Because
  `podman save` serializes podman-side state that Gradle can't snapshot, keying the
  task on the image *tag* alone would let the archive go stale after a same-tag
  rebuild. When you declare an image through `images { }` with `includeDigest` (the
  default), the plugin wires that image's reference file — whose digest line is
  refreshed every build — into the save task as a content-identity input, so the
  archive is re-saved exactly when the image content changes and stays up-to-date
  otherwise.

If you want a different policy (e.g. force a save to always run), use the standard
Gradle levers like `outputs.upToDateWhen { false }` on your task.

---

## Configuration cache & laziness

The plugin is compatible with the Gradle
[configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html).
Two properties make that work:

- **No `Project` access at execution time.** Process execution goes through the
  injected `ExecOperations` service, and there is no `project.exec`, no
  `project.getXxx()` inside any `@TaskAction`. (The one place a build references
  `getProject()` — `ContainerBuildTask`'s constructor, to default the context
  directory — runs at *configuration* time, which is allowed.)
- **All inputs are `Provider`-backed**, so their values are resolved lazily and
  serialized into the configuration-cache entry rather than recomputed against a
  live project model.

A functional test (`ContainerPluginFunctionalSpec`) runs a task twice with
`--configuration-cache` and asserts the second run reports
`Reusing configuration cache.`

---

## Usage

Apply the plugin:

```groovy
plugins {
    id 'io.github.nhwalker.container' version '0.1.0'
}
```

Configure shared defaults (optional):

```groovy
container {
    executable    = '/usr/local/bin/podman'   // default: 'podman' on PATH
    globalOptions = ['--log-level', 'info']    // inserted before every subcommand
    connection    = 'my-remote'                // adds --connection my-remote
}
```

Declare the tasks you need:

```groovy
import io.github.nhwalker.container.gradle.tasks.*

tasks.register('buildImage', ContainerBuildTask) {
    contextDirectory = layout.projectDirectory.dir('src/main/docker')
    containerfile    = layout.projectDirectory.file('src/main/docker/Containerfile')
    tags             = ["example/app:${version}", 'example/app:latest']
    buildArgs        = ['VERSION': version.toString()]
    labels           = ['org.opencontainers.image.source': 'https://example.com/repo']
    platform         = 'linux/amd64'
    pull             = true
}

tasks.register('tagImage', ContainerTagTask) {
    sourceImage  = "example/app:${version}"
    targetImages = ["registry.example.com/example/app:${version}"]
}

tasks.register('pushImage', ContainerPushTask) {
    dependsOn 'tagImage'
    image     = "registry.example.com/example/app:${version}"
    tlsVerify = true
}

tasks.register('runApp', ContainerRunTask) {
    image         = 'example/app:latest'
    containerName = 'app'
    detach        = true
    remove        = true
    ports         = ['8080:8080']
    environment   = ['SPRING_PROFILES_ACTIVE': 'dev']
    command       = ['--server.port=8080']
}

tasks.register('stopApp', ContainerStopTask) {
    containers      = ['app']
    ignoreExitValue = true   // tolerate "no such container"
}

tasks.register('saveImage', ContainerSaveTask) {
    image      = 'example/app:latest'
    outputFile = layout.buildDirectory.file('images/app.tar')
    format     = 'oci-archive'
}

tasks.register('loadImage', ContainerLoadTask) {
    inputFile = layout.buildDirectory.file('images/app.tar')
}
```

### Copying files out of an image

Extract build artifacts or config from an image without running it. The task
creates a throwaway container from the image, copies each path out, and removes
the container automatically:

```groovy
tasks.register('extractArtifacts', ContainerCopyFromImageTask) {
    image = 'example/app:latest'
    paths = [
        // path inside the image : destination on the host
        '/app/app.jar'    : layout.buildDirectory.file('extracted/app.jar').get().asFile.path,
        '/etc/app/config' : layout.buildDirectory.dir('extracted/config').get().asFile.path,
    ]
    copyOptions = ['--archive']   // preserve uid/gid/permissions (optional)
}

// Or copy from a container that already exists (no create/remove happens):
tasks.register('extractFromRunning', ContainerCopyFromImageTask) {
    container = 'app'
    paths = ['/var/log/app.log': layout.buildDirectory.file('logs/app.log').get().asFile.path]
}
```

### The generic escape hatch

For any subcommand without a dedicated task, use `ContainerExecTask`, optionally
capturing its output:

```groovy
tasks.register('listImages', ContainerExecTask) {
    arguments     = ['images', '--format', '{{.Repository}}:{{.Tag}}']
    captureOutput = true
    doLast { println standardOutput }
}
```

### Inspecting a command without running it

```groovy
tasks.register('buildImage', ContainerBuildTask) {
    tags   = ['example/app:latest']
    dryRun = true   // prints "[dry-run] podman build -t example/app:latest ." and exits
}
```

---

## Managing images as dependencies

Beyond the standalone tasks above, the plugin can model **images as variant-aware
Gradle dependencies**, so an image built in one project (or build) can be consumed
as the base image of another, transferred as an archive, aggregated for a push, or
published to a Maven repository — all through Gradle's normal dependency machinery.

Declare images in the `container { images { } }` container. Each image automatically
gets a build task (`build<Name>Image`), a reference-writing task
(`write<Name>ImageReference`), an optional save task (`save<Name>Image` when
`createArchive = true`), and the consumable configurations other projects resolve.

```groovy
container {
    images {
        base {
            containerfile = file('base/Containerfile')
            tags          = ["com.example/base:${version}"]
            createArchive = true            // also expose an archive (tar) variant
            archiveFormat = 'oci-archive'   // default
        }
    }
}
```

### How it is modeled

An image is a **component variant**, exactly like the Java plugin's main/sources/javadoc
jars:

- **Module identity** is the Gradle project's own `group:name` coordinate (its implicit
  capability). No custom capabilities are added — this is what lets composite builds
  substitute it automatically.
- **Which image** within a module is chosen by the `imageName` *attribute*; **which form**
  (reference vs. archive) by `imageType` (and `archiveFormat`). So one project can publish
  several images under one coordinate, each an attribute-selected variant with its own
  artifact classifier.
- The **reference** variant is a small file holding the image coordinate (`name:tag`) and,
  by default, its digest (`name@sha256:…`) — a coordinate pointer; the image itself stays
  in podman's local storage. The **archive** variant carries the actual `podman save` tar.

The custom attributes live in `io.github.nhwalker.container.gradle.dependency.ContainerAttributes`
and are isolated from the JVM ecosystem by a required `ecosystem=container-image` marker.

### Base images (`FROM`)

Declare a base image with `from(...)`. This wires build **ordering** (the base is built
first) and injects the resolved base reference into the dependent build as a
`--build-arg`, read at execution time:

```groovy
container {
    images {
        base { tags = ["com.example/base:${version}"] }
        app {
            containerfile = file('app/Containerfile')   // ARG BASE_IMAGE / FROM ${BASE_IMAGE}
            tags          = ["com.example/app:${version}"]

            from 'BASE_IMAGE', images.base               // sibling image, same project
            // from 'BASE_IMAGE', project(':base')       // another project, same build
            // from 'BASE_IMAGE', 'com.example:base:1.0' // external coordinate / composite
            // from 'BASE_IMAGE', project(':multi'), 'runtime'  // pick one of several images
        }
    }
}
```

The consumer's `Containerfile` opts in to the injected value:

```dockerfile
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
```

### Archive transfer

Set `createArchive = true` on the producer, then resolve the `archive` variant in the
consumer and feed it to `ContainerLoadTask` (or a push):

```groovy
import io.github.nhwalker.container.gradle.dependency.ContainerAttributes

configurations {
    incomingImage {
        canBeConsumed = false; canBeResolved = true
        attributes {
            attribute(ContainerAttributes.ECOSYSTEM, ContainerAttributes.ECOSYSTEM_VALUE)
            attribute(ContainerAttributes.IMAGE_TYPE, ContainerAttributes.IMAGE_TYPE_ARCHIVE)
        }
    }
}
dependencies { incomingImage project(':base') }

tasks.register('loadBase', io.github.nhwalker.container.gradle.tasks.ContainerLoadTask) {
    inputFile = layout.file(provider { configurations.incomingImage.singleFile })
}
```

### Aggregate & push

An aggregator project depends on several image projects and iterates the resolved
references (or archives):

```groovy
configurations {
    allRefs {
        canBeConsumed = false; canBeResolved = true
        attributes {
            attribute(ContainerAttributes.ECOSYSTEM, ContainerAttributes.ECOSYSTEM_VALUE)
            attribute(ContainerAttributes.IMAGE_TYPE, ContainerAttributes.IMAGE_TYPE_REFERENCE)
        }
    }
}
dependencies { allRefs project(':base'); allRefs project(':app') }
// a push task reads each file's first line (the coordinate)
```

### Publishing

The plugin contributes one software component, `container`, aggregating **every** image's
variants. Attach it to a `MavenPublication`:

```groovy
plugins { id 'io.github.nhwalker.container'; id 'maven-publish' }
group = 'com.example'; version = '1.0'

container { images {
    foo { tags = ['example/foo:1.0']; createArchive = true }
    bar { tags = ['example/bar:1.0'] }
} }

publishing { publications { maven(MavenPublication) { from components.container } } }
```

The whole project publishes as one module whose Gradle Module Metadata carries every
image as an attribute-selected variant (`imageName × imageType`) with distinct artifact
classifiers, so downstream builds resolve any individual image.

### Composite builds

Because identity is the project coordinate and image selection is an attribute, composite
builds work with **no special wiring on either side and no `dependencySubstitution` rules**.
Declare the base as an external coordinate; locally, `includeBuild` transparently
substitutes it with the included project:

```groovy
// consumer settings.gradle
includeBuild '../platform'
// consumer build.gradle — identical whether resolved from a repo or substituted
container { images { app { from 'BASE_IMAGE', 'com.example:platform:1.0', 'runtime' } } }
```

### Exposing image coordinates to Java (`generateJavaRefs`)

When a Java plugin is applied and `generateJavaRefs = true`, the plugin generates a
`<ProjectName>Images` interface holding each declared image's full docker identifier
(its primary/first tag) as a `public static final String` constant. The constant name
is the image name in `UPPER_SNAKE_CASE`. The interface is added to the `main` source
set (so it compiles with your code) and is regenerated whenever an image is built.

```groovy
plugins { id 'java'; id 'io.github.nhwalker.container' }
group = 'com.example'                    // becomes the generated package

container {
    generateJavaRefs = true
    // javaRefsPackage = 'com.example.images'   // override (defaults to project group)
    images {
        app       { tags = ['example/app:1.0', 'example/app:latest'] }
        webServer { tags = ['example/web:2.0'] }
    }
}
```

When the `eclipse` plugin is also applied, `eclipseClasspath` depends on the image
build (and thus the generator), so regenerating the Eclipse classpath builds the
images and the generated source folder exists for the IDE to pick up.

For a project named `fixture` this generates `com.example.FixtureImages`:

```java
package com.example;

// Generated by the io.github.nhwalker.container plugin. Do not edit.
public interface FixtureImages {
    public static final String APP = "example/app:1.0";
    public static final String WEB_SERVER = "example/web:2.0";
}
```

### Low-level plumbing

The DSL is built on public helpers in
`io.github.nhwalker.container.gradle.dependency.ContainerDependencies`
(`registerSchema`, `referenceElements`, `archiveElements`, `baseImageBucket`,
`resolvableReferences`) plus the `ContainerImageReferenceTask` type, so you can wire your own
tasks into the same configurations without the `images { }` container.

---

## Helm Plugin

The same build ships a second, independently applied plugin,
`io.github.nhwalker.helm`, that brings the container plugin's design to
[Helm](https://helm.sh/): it **drives the `helm` CLI** (it is not an API binding),
**everything is lazy** (`Provider`-based, configuration-cache friendly), and it
**contributes task *types*** rather than tasks. Execution flows through an
`AbstractHelmTask` base that assembles `<executable> <globalOptions> <subcommand>`
and runs it via `ExecOperations`, exactly like `AbstractContainerTask`.

It models three task types — the common chart build surface plus an escape hatch:

| Task type | `helm` command | Purpose |
| --- | --- | --- |
| `HelmLintTask` | `helm lint` | Examine a chart for issues (`--strict`, `--values`). |
| `HelmPackageTask` | `helm package` | Package a chart directory into a versioned `.tgz`. |
| `HelmExecTask` | *any* | Generic escape hatch for unmodeled subcommands. |

`HelmPackageTask` packages into a private temp directory and then moves the single
produced archive to a stable, declared `packagedChart` output path (helm itself
names the file `<chartName>-<version>.tgz` from `Chart.yaml`), so the result can be
tracked as an output and shared as a dependency.

### The `helm { charts { } }` DSL

Declaring a chart registers, under the `helm` task group, a **stage** task (a
`Sync` that assembles the chart and its subchart dependencies into
`build/helm/<name>/staged`, keeping your sources pristine), a **package** task
(`build/helm/<name>/<name>.tgz`), an optional **lint** task, and a consumable
variant so the packaged chart can be shared with other projects.

```groovy
plugins { id 'io.github.nhwalker.helm' }

group   = 'com.example'
version = '1.0.0'

helm {
    // executable = '/usr/local/bin/helm'           // optional; defaults to `helm` on PATH
    // globalOptions = ['--namespace', 'platform']  // inserted before every subcommand
    charts {
        api {
            chartDirectory = layout.projectDirectory.dir('src/main/helm/api') // has Chart.yaml
            chartVersion   = project.version.toString()   // optional → --version
            appVersion     = '2.3.4'                       // optional → --app-version
            // lint = false                                // skip the auto lint task
        }
    }
}
```

(`chartDirectory` defaults to `src/main/helm/<name>`.) This yields the tasks
`stageApiChart`, `packageApiChart`, and `lintApiChart`.

### Build-time value injection (`preValues`)

`preValues` is a key/value map (the helm counterpart of the container plugin's `buildArgs`).
During staging — before `helm package`/`helm lint` run — each placeholder of the
form <code>{{ .PreValues.&lt;name&gt; }}</code> (whitespace inside the braces is
ignored) in the chart's `Chart.yaml` and `values.yaml` is replaced with the
matching value. Substitution happens on the staged copy, so your source tree is
never modified, and the map is a tracked input so changing a value re-stages the
chart. Placeholders whose name is not in the map are left untouched.

```groovy
helm {
    charts {
        api {
            preValues = [
                'ChartVersion': project.version.toString(),
                'AppTag'      : 'sha-abc123',
            ]
        }
    }
}
```

```yaml
# src/main/helm/api/Chart.yaml
apiVersion: v2
name: api
version: {{ .PreValues.ChartVersion }}     # → version: 1.0.0

# src/main/helm/api/values.yaml
image:
  tag: {{ .PreValues.AppTag }}             # → tag: sha-abc123
```

### Sharing charts as dependencies

Charts are modeled with the same "one module, several attribute-selected variants"
approach as container images: module identity stays at the project's `group:name`
coordinate, the `io.github.nhwalker.helm.chartName` attribute selects which chart,
and `io.github.nhwalker.helm.ecosystem` fences helm variants off from the JVM
ecosystem. A `from(...)` dependency resolves another chart's packaged `.tgz` and
stages it into this chart's `charts/` subchart directory before packaging — Gradle
orders the producer first automatically.

```groovy
helm {
    charts {
        platform {
            chartDirectory = layout.projectDirectory.dir('src/main/helm/platform')
            from project(':api')                       // another project in this build
            // from 'com.example:billing-chart:1.4.0'  // external / published coordinate
            // from(project(':services'), 'api')        // pin one chart of a multi-chart producer
            // from charts.base                         // a sibling chart in this project
        }
    }
}
```

Your `platform/Chart.yaml` still lists the subchart under `dependencies:`; the
plugin supplies the archive bytes into `charts/`. Publish the charts the same way
as container images — `from components.helm` in a `MavenPublication`.

### Bundling charts in the jar and exposing them to Java (`generateJavaRefs`)

When a Java plugin is applied and `generateJavaRefs = true`, the plugin bundles each
packaged chart into the jar at `charts/<chart>.tgz` and generates a `<ProjectName>Charts`
interface holding each chart's classpath resource path as a `public static final String`
constant. The constant name is the chart name in `UPPER_SNAKE_CASE`. The interface is
added to the `main` source set (so it compiles with your code) and is regenerated
whenever a chart is packaged. When the `eclipse` plugin is also applied,
`eclipseClasspath` packages the charts and regenerates the refs, mirroring the
container plugin.

```groovy
plugins { id 'java'; id 'io.github.nhwalker.helm' }
group = 'com.example'                    // becomes the generated package

helm {
    generateJavaRefs = true
    // javaRefsPackage = 'com.example.charts'   // override (defaults to project group)
    charts {
        api      { /* src/main/helm/api */ }
        webProxy { /* src/main/helm/webProxy */ }
    }
}
```

For a project named `fixture` this generates `com.example.FixtureCharts`, and the
packaged charts ship inside the jar so they can be loaded at runtime:

```java
package com.example;

// Generated by the io.github.nhwalker.helm plugin. Do not edit.
public interface FixtureCharts {
    public static final String API = "charts/api.tgz";
    public static final String WEB_PROXY = "charts/webProxy.tgz";
}

// e.g. getClass().getClassLoader().getResourceAsStream(FixtureCharts.API)
```

### Manual task types (no DSL)

```groovy
import io.github.nhwalker.helm.gradle.tasks.*

tasks.register('lintApi', HelmLintTask) {
    chartDirectory = layout.projectDirectory.dir('src/main/helm/api')
    strict = true
    valuesFiles.from('ci/values.yaml')
}
tasks.register('packageApi', HelmPackageTask) {
    chartDirectory = layout.projectDirectory.dir('src/main/helm/api')
    chartVersion   = '1.0.0'
    packagedChart  = layout.buildDirectory.file('helm/api/api.tgz')
}
tasks.register('helmVersion', HelmExecTask) {
    arguments = ['version', '--short']
}
```

---

## Building from source

```bash
./gradlew build
```

This compiles the plugin, runs the Spock unit tests (which assert exact command
assembly) and the Gradle TestKit functional tests (which run a fake `podman`
script and verify the recorded arguments, dry-run behavior, and configuration
cache reuse), and runs Gradle's `validatePlugins` checks.

---

## License

Released into the public domain under the [Unlicense](LICENSE).
