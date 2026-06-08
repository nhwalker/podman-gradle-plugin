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

This build also ships two sibling plugins that follow the same design: a **Helm**
plugin (`io.github.nhwalker.helm`) for the `helm` CLI — see
[Helm Plugin](#helm-plugin) — and a **Generic Artifacts** plugin
(`io.github.nhwalker.artifacts`) that generalizes the "publish/consume artifacts
as variant-aware dependencies" machinery to *any* file — see
[Generic Artifacts Plugin](#generic-artifacts-plugin).

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

The `@TaskAction` method runs the task's primary subcommand through the shared
`runSubcommand(...)` primitive and stashes any captured output:

```java
@TaskAction
public void execute() {
    String captured = runSubcommand(buildSubcommand(), getCaptureOutput().get());
    if (captured != null) {
        capturedStandardOutput = captured;   // exposed via task.getStandardOutput()
    }
}
```

`runSubcommand` is where the real work happens. Because it takes the subcommand as a
parameter, the tasks that issue more than one podman invocation (`tag`,
copy-from-image) call it repeatedly — each call independently honoring
`dryRun`/`ignoreExitValue`:

```java
protected String runSubcommand(List<String> subcommand, boolean captureStdout) {
    List<String> command = assembleCommandFor(subcommand);

    if (getDryRun().get()) {                       // 1. dry-run short-circuit
        getLogger().lifecycle("[dry-run] {}", String.join(" ", command));
        return null;
    }

    getLogger().info("Executing: {}", String.join(" ", command));

    var buffer = captureStdout                     // 2. optional stdout capture
            ? new ByteArrayOutputStream() : null;

    ExecResult result = getExecOperations().exec(spec -> {   // 3. run podman
        spec.commandLine(command);
        spec.setIgnoreExitValue(getIgnoreExitValue().get());
        if (buffer != null) spec.setStandardOutput(buffer);
    });

    if (getIgnoreExitValue().get())                // 4. exit handling
        getLogger().info("{} exited with code {}", getExecutable().get(),
                         result.getExitValue());
    else
        result.assertNormalExitValue();

    return buffer != null                          // 5. return captured stdout
            ? buffer.toString(StandardCharsets.UTF_8) : null;
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
4. **Exit handling** — with `ignoreExitValue = true`, the exit code is merely
   logged so the build continues (handy for idempotent cleanup like "stop a
   container that may not exist"); otherwise `assertNormalExitValue()` fails the
   task on any non-zero code.
5. **Return** — the captured output (decoded as UTF-8) is returned to `execute()`,
   which stashes it so `task.getStandardOutput()` can hand it to `doLast { }`
   blocks.

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
  default), the plugin wires that image's reference file — whose digest is refreshed
  every build — into the save task as a content-identity input, so the archive is
  re-saved exactly when the image content changes and stays up-to-date otherwise.

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
- The **reference** variant is a small file holding a single-line image reference — the
  coordinate `name:tag`, with the digest appended in place by default to give the canonical
  `name:tag@sha256:…` form — a coordinate pointer; the image itself stays in podman's local
  storage. The **archive** variant carries the actual `podman save` tar.

Images are published and consumed as **generic artifacts** — they reuse the
`io.github.nhwalker.artifacts` plugin's model. The variant's Maven classifier is
`<image>-reference` (a `txt` file) or `<image>` (a `tar`), a required
`ecosystem=generic-artifact` marker fences the variants off from the JVM ecosystem, and
the container free String attributes — `io.github.nhwalker.container.imageName`,
`imageType`, and `archiveFormat`, whose keys live in
`io.github.nhwalker.container.gradle.dependency.ContainerAttributes` — refine which image
and which form a request selects. A side benefit: any project can consume an image
through the generic `genericArtifacts { consume { } }` DSL, not just the container plugin.

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

Set `createArchive = true` on the producer, then consume the `archive` variant — the
archive's classifier is the image name — and feed it to `ContainerLoadTask` (or a push).
Apply `io.github.nhwalker.artifacts` alongside the container plugin and use its
`consume { }` DSL:

```groovy
plugins { id 'io.github.nhwalker.container'; id 'io.github.nhwalker.artifacts' }

genericArtifacts {
    consume {
        // classifier '<image>' selects the archive (tar); '<image>-reference' the reference.
        incomingImage { from project(':base'); classifier = 'base' }
    }
}

tasks.register('loadBase', io.github.nhwalker.container.gradle.tasks.ContainerLoadTask) {
    inputFile = layout.file(provider { genericArtifacts.consume.incomingImage.files.singleFile })
}
```

### Multi-image archives (`archives { }`)

Where per-image `createArchive` exports **one tar per image**, the `container { archives { } }`
container bundles **several images into one tar** with a single `podman save img1 img2 …` — the
"offline bundle you `podman load` in one shot" shape. Each archive gets a `save<Name>Archive` task
and publishes as an `archive` variant of the same aggregated component (classifier defaulting to the
archive name; `imageName=<archive>`, `imageType=archive`, `archiveFormat=<format>`), selectable
exactly like a single-image archive.

Members are a free combination, saved in declaration order:

```groovy
container {
    images { app { /* … */ } }
    archives {
        bundle {
            image images.app                              // a sibling image in this project
            from project(':base')                         // a cross-project image reference
            from project(':multi'), 'runtime'             // one image of a multi-image producer
            referenceFile file('refs/legacy-ref.txt')     // a published name:tag@digest file
            image 'docker.io/library/alpine:3.20'         // an arbitrary literal image

            // format          = 'oci-archive' // default; 'docker-archive' also supported
            // pullPolicy      = 'missing'     // default; passed to `podman pull --policy`
            // defaultArtifact = true          // publish the bundle as the bare-GAV main artifact
        }
    }
}
```

Reference-backed members (`image(sibling)`, `from(...)`, `referenceFile(...)`) carry the producing
image's digest, so the archive **re-saves when their content changes** — the same content-pinning a
single-image archive uses. Literal members (`image('name:tag')`) are pinned only by their tag string.
Before saving, the task runs one `podman pull --policy <pullPolicy>` (default `missing`) over the
members, fetching anything not already in local storage; this runs only when the task executes, so it
never affects the up-to-date check. (`always`/`newer` fail for local-only tags like sibling images;
`never` errors if a member is absent.)

### Aggregate & push

An aggregator project depends on several image projects and iterates the resolved
references (or archives). Select the form with the `imageType` free attribute instead of
a classifier, so a single request gathers every reference variant regardless of image
name:

```groovy
plugins { id 'io.github.nhwalker.container'; id 'io.github.nhwalker.artifacts' }

genericArtifacts {
    consume {
        allRefs {
            from project(':base'); from project(':app')
            attribute 'io.github.nhwalker.container.imageType', 'reference'
        }
    }
}
// a push task reads each file's single line (name:tag@sha256:…) from genericArtifacts.consume.allRefs.files
```

### Publishing

The container, helm, and generic-artifacts plugins all contribute their variants to **one
shared, aggregated software component, `genericArtifacts`** (so a project applying any mix
of them publishes a single coherent module). Attach it to a `MavenPublication`:

```groovy
plugins { id 'io.github.nhwalker.container'; id 'maven-publish' }
group = 'com.example'; version = '1.0'

container { images {
    foo { tags = ['example/foo:1.0']; createArchive = true }
    bar { tags = ['example/bar:1.0'] }
} }

publishing { publications { maven(MavenPublication) { from components.genericArtifacts } } }
```

The whole project publishes as one module whose Gradle Module Metadata carries every
image as an attribute-selected variant (`imageName × imageType`) with distinct artifact
classifiers, so downstream builds resolve any individual image.

When the `java` plugin is also applied, the image variants are **additionally** folded into
`components.java`, so a single `from components.java` ships the jar (+ sources/javadoc) and
the images in one module. Publish exactly one of the two components per repository — both
resolve to the same `group:name:version`. (The previous per-plugin `components.container`
component has been **removed**; migrate `from components.container` to
`from components.genericArtifacts`, or to `from components.java` when publishing alongside
a jar.)

> **One module, unique coordinates.** Because everything shares one module, no two
> published artifacts may collide on the same Maven *classifier + extension*. Distinct
> extensions (txt/tar/tgz/jar) keep the common cases apart; give clashing artifacts
> distinct names/classifiers across the plugins within a project.

#### A default (unclassified) artifact

By default every variant publishes under a Maven classifier, so the bare
`group:name:version` has no primary file (unless `java` contributes the jar). Mark one
artifact as the module's **default** — published without a classifier, addressable as the
bare GAV — with `defaultArtifact`. For an image, select which of its two artifacts:

```groovy
container { images { foo {
    tags = ['example/foo:1.0']; createArchive = true
    defaultArtifact = 'archive'   // or 'reference'  ->  com.example:foo:1.0 resolves to foo-1.0.tar
} } }
```

The variant's `classifier` attribute is unchanged, so Gradle attribute selection is
unaffected; only the published file's Maven classifier is cleared (and the POM `packaging`
becomes that artifact's extension). At most **one** artifact per project — across the
container/helm/artifacts plugins — may be the default; a second is an error. When `java` is
applied, the jar remains the primary artifact of `components.java`, so `defaultArtifact` is
meant for the `components.genericArtifacts` publication.

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

### Exposing image coordinates to Java (`javaReference`)

When a Java plugin is applied, the plugin generates, per source set,
a `<ProjectName>Images[<SourceSet>]` interface holding the resolved reference of each image that
**opts in** with `javaReference(...)` as a `public static final String` constant. The constant name
is the image name in `UPPER_SNAKE_CASE`; its value is the image's reference-file contents — the
coordinate `name:tag`, digest-pinned to `name:tag@sha256:…` when that image's `includeDigest` is on
(the default). Because the value comes from the reference file, generating the interface builds and
inspects the opted-in images.

`javaReference()` targets the `main` source set (so the constant compiles with your production code);
`javaReference('test')` (or any source-set name) targets that source set instead. This **scopes the
container-engine dependency to that source set** — an image exposed only to `test` is built when
`compileTestJava`/`test` runs, leaving `compileJava`/`jar`/`assemble` independent of podman. An image
that calls neither contributes no constant and stays build-decoupled.

```groovy
plugins { id 'java'; id 'io.github.nhwalker.container' }
group = 'com.example'                    // becomes the generated package

container {
    // referencesPackage = 'com.example.images'   // override (defaults to project group)
    // referencesClassName = 'MyImages'           // override the interface name (default <ProjectName>Images)
    images {
        app       { tags = ['example/app:1.0', 'example/app:latest']; javaReference() }       // -> main
        webServer { tags = ['example/web:2.0'];                        javaReference() }       // -> main
        itBase    { tags = ['example/it-base:1.0'];                    javaReference('test') } // -> test only
    }
}
```

Under the hood this is the same artifact-agnostic `GenerateReferencesTask` the helm and
generic-artifacts plugins use, fed each image's reference-file contents — so `javaReference()` is
the built-in convenience that captures this project's own images, the same way
[`references` / `fromFile`](#capturing-a-files-contents-fromfile) captures another project's.

When the `eclipse` plugin is also applied, `eclipseClasspath` depends on the image build + reference
write (and thus the generator), so regenerating the Eclipse classpath builds the images and the
generated source folder exists for the IDE to pick up.

For a project named `fixture` this generates `com.example.FixtureImages` (main) and
`com.example.FixtureImagesTest` (test):

```java
package com.example;

// Generated by the io.github.nhwalker.container plugin. Do not edit.
public interface FixtureImages {
    public static final String APP = FixtureImagesLoader.load("APP", "example/app:1.0@sha256:…");
    public static final String WEB_SERVER = FixtureImagesLoader.load("WEB_SERVER", "example/web:2.0@sha256:…");
    // … plus a private nested FixtureImagesLoader (omitted) that resolves each value at runtime.
}
```

Each constant resolves its value at class-init time, falling back to the generated default — so a
deployment can redirect an image coordinate without recompiling. See
[Overriding generated values at runtime](#overriding-generated-values-at-runtime).

Each plugin names its interface `<ProjectName><Domain>` with its own `<Domain>` segment —
`Images` here, `Charts` for helm, `References` for generic-artifacts — so the three never
collide and can all be applied in one project. The name is customizable via
`referencesClassName` (the same property name on all three plugins; the `<Domain>` default is
just a convention), and a non-`main` source set appends the capitalized source-set name.

### Low-level plumbing

The DSL is built on the generic artifacts plumbing in
`io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies`
(`registerSchema`, `registerAttributeKey`, `elements`, `dependencyBucket`, `resolvable`) —
the container free-attribute keys and values live in
`io.github.nhwalker.container.gradle.dependency.ContainerAttributes` — plus the
`ContainerImageReferenceTask` type, so you can wire your own tasks into the same
configurations without the `images { }` container.

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

Charts are published and consumed as **generic artifacts**, the same way as container
images — they reuse the `io.github.nhwalker.artifacts` plugin's model. Module identity
stays at the project's `group:name` coordinate, the variant's Maven classifier is the
chart name, a required `ecosystem=generic-artifact` marker fences helm variants off from
the JVM ecosystem, and the `io.github.nhwalker.helm.chartName`/`chartType` free
attributes select which chart and which form a request resolves. A `from(...)` dependency
resolves another chart's packaged `.tgz` and stages it into this chart's `charts/`
subchart directory before packaging — Gradle orders the producer first automatically.

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
as container images — the chart variants are contributed to the shared
`genericArtifacts` component (and folded into `components.java` when the `java` plugin is
applied), so use `from components.genericArtifacts` (or `from components.java`) in a
`MavenPublication`. (The previous per-plugin `components.helm` component has been
**removed**; migrate accordingly.) A chart can be the module's default (unclassified)
artifact with `helm { charts { app { defaultArtifact = true } } }` — see
[the container Publishing section](#publishing) for the shared rules.

### Bundling charts in the jar and exposing them to Java

Bundling is opted into per-chart with `importResourcesTask()` — the same method the
generic artifacts `produce`/`consume` DSL uses. When a Java plugin is applied, it stages
the packaged chart into a generated resource folder laid out as `charts/<chart>.tgz` and
registers it as a resource directory (the `main` source set by default; pass a name such
as `importResourcesTask('test')` to target another) — so the chart ships in the jar at
that path *and* appears as a resource source folder (available when running inside the
IDE).

Bundling a chart additionally generates a `<ProjectName>Charts`
interface holding each bundled chart's classpath resource path as a
`public static final String` constant (the constant name is the chart name in
`UPPER_SNAKE_CASE`), added to the source set the chart was bundled into so it compiles
with your code. Charts bundled into a non-`main` source set land in a suffixed interface
(`<ProjectName>ChartsTest`, etc.). The name is customizable via `referencesClassName`. Its
`Charts` domain keeps it distinct from the container (`Images`) and generic-artifacts
(`References`) interfaces, so all three can coexist in one project. When the `eclipse` plugin
is also applied, `eclipseClasspath` carries both the generated source folder and the chart
resource folders.

```groovy
plugins { id 'java'; id 'io.github.nhwalker.helm' }
group = 'com.example'                    // becomes the generated package

helm {
    // referencesPackage = 'com.example.charts'   // override (defaults to project group)
    // referencesClassName = 'MyCharts'           // override the interface name (default <ProjectName>Charts)
    charts {
        api      { importResourcesTask() }   // src/main/helm/api, bundled at charts/api.tgz
        webProxy { importResourcesTask() }   // src/main/helm/webProxy
    }
}
```

For a project named `fixture` this generates `com.example.FixtureCharts`, and the
packaged charts ship inside the jar so they can be loaded at runtime:

```java
package com.example;

// Generated. Do not edit.
public interface FixtureCharts {
    public static final String API = FixtureChartsLoader.load("API", "charts/api.tgz");
    public static final String WEB_PROXY = FixtureChartsLoader.load("WEB_PROXY", "charts/webProxy.tgz");
    // … plus a private nested FixtureChartsLoader (omitted) that resolves each value at runtime.
}

// e.g. getClass().getClassLoader().getResourceAsStream(FixtureCharts.API)
```

Each constant resolves its value at class-init time, falling back to the generated default; a
chart's resource path can be overridden at runtime. See
[Overriding generated values at runtime](#overriding-generated-values-at-runtime).

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

## Generic Artifacts Plugin

The same build ships a third, independently applied plugin,
`io.github.nhwalker.artifacts`, that **generalizes** the "share an artifact between
projects as a variant-aware dependency" machinery the container and helm plugins use
into a reusable, artifact-agnostic plugin. Where those plugins know about images and
charts, this one knows about *nothing in particular* — you hand it any file(s) and a
**classifier** (plus optional free attributes), and it publishes and consumes them
through Gradle's normal dependency model.

### Why: a classifier that survives composite builds

In Maven, several artifacts share one module coordinate and are told apart by a
**classifier** (`group:name:version:classifier`). That works in a plain Maven
repository, but a Maven classifier is **not** a first-class part of Gradle's variant
model: when a Gradle **composite build** substitutes an external coordinate with a
locally included project, the classifier selector is lost — the included project has
no notion of it, so the substitution can't pick the right artifact.

Gradle **attributes** do survive substitution: the included project exposes consumable
configurations carrying the same attributes, and Gradle's variant matcher re-selects
them. This plugin therefore models a classifier as an **attribute**
(`io.github.nhwalker.artifacts.classifier`), optionally refined by any number of
user-declared free String attributes. The result is a classifier that behaves
identically whether the dependency resolves from a Maven repo, another project in the
same build, or an included build — with **no `dependencySubstitution` rules** on either
side.

### How it is modeled

Exactly like container images and helm charts — "one module, several attribute-selected
variants":

- **Module identity** stays at the project's implicit `group:name` coordinate (its
  default capability); no custom capabilities are added, which is what lets composite
  builds substitute it automatically.
- **Which artifact** within the module is chosen by the `classifier` attribute plus any
  free attributes you declare. Each produced artifact becomes one consumable
  configuration (one variant) whose Maven classifier defaults to the `classifier`
  value, so it is addressable by classifier in a plain Maven repo too.
- A required `ecosystem=generic-artifact` marker fences these variants off from the JVM
  ecosystem. The attributes live in
  `io.github.nhwalker.artifacts.gradle.dependency.ArtifactsAttributes`.

The plugin contributes the shared `genericArtifacts` software component, aggregating every
produced artifact's variant for publishing — the **same** component the container and helm
plugins contribute to, so a project applying several of them publishes one coherent module.
When the `java` plugin is applied, the variants are also folded into `components.java`, so
`from components.java` ships the jar and the produced artifacts together (publish only one
of the two components per repository). Applying the plugin adds **no tasks** — it only
registers the `genericArtifacts { }` extension and the component.

A produced artifact can be the module's default (unclassified) artifact — addressable as
the bare `group:name:version` — with `produce { report { defaultArtifact = true } }`; at
most one artifact per project (across all three plugins) may be the default. See
[the container Publishing section](#publishing) for the shared rules.

> **Note on the extension name:** Gradle's `Project` already has a built-in
> `artifacts { }` method (the `ArtifactHandler`), which would shadow an extension named
> `artifacts`. The extension is therefore accessed as **`genericArtifacts`**, while the
> plugin id remains `io.github.nhwalker.artifacts`.

### Producing artifacts

Declare artifacts under `genericArtifacts { produce { } }`. Each element's name is its
default classifier. When the artifact notation is a task output, the producing task is
wired as a build dependency automatically; for a plain file, set `builtBy(...)` so both
publishing and consumption still trigger the producer.

```groovy
plugins { id 'io.github.nhwalker.artifacts'; id 'maven-publish' }
group = 'com.example'; version = '1.0'

def reportFile = layout.buildDirectory.file('reports/site.html')
tasks.register('makeReport') {
    outputs.file(reportFile)
    doLast { reportFile.get().asFile.text = '<html>…</html>' }
}

genericArtifacts {
    produce {
        report {                                  // classifier defaults to 'report'
            attribute 'flavor', 'html'            // optional free attributes
            artifact reportFile, { builtBy 'makeReport' }
        }
        bundle {
            classifier = 'dist'                   // override the classifier
            artifact tasks.makeBundle            // task output → build dependency inferred
        }
    }
}

publishing { publications { maven(MavenPublication) { from components.genericArtifacts } } }
```

The whole project publishes as one module whose Gradle Module Metadata carries each
produced artifact as an attribute-selected variant with a distinct classifier.

#### Bundling produced artifacts into your jar and exposing them to Java

A produced artifact can also be bundled into this project's own jar resources with
`importResourcesTask()` (the same method the `consume` side uses). When a Java plugin is
applied it stages the produced file(s) into a generated resource folder registered on a
source set's resources (the `main` source set by default; pass a name such as
`importResourcesTask('test')` to target another), so they ship in the jar and appear on
the eclipse classpath. The copy-spec `Action` overload places files under a subdirectory,
e.g. `importResourcesTask { into 'reports' }`.

Bundling an artifact additionally generates a `<ProjectName>References`
interface holding each bundled artifact's classpath resource path as a
`public static final String` constant (named after the element in `UPPER_SNAKE_CASE`),
compiled with the source set it was bundled into (artifacts bundled into a non-`main`
source set land in a suffixed `<ProjectName>ReferencesTest`, etc.). The name is customizable
via `referencesClassName`; its `References` domain keeps it distinct from the container
(`Images`) and helm (`Charts`) interfaces, so all three can coexist in one project.

```groovy
plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
group = 'com.example'

genericArtifacts {
    // referencesPackage = 'com.example'        // override (defaults to project group)
    // referencesClassName = 'MyRefs'           // override the interface name (default <ProjectName>References)
    produce {
        report {
            artifact tasks.makeReport.outputFile
            importResourcesTask { into 'reports' }   // bundled at reports/<file> in the jar
        }
    }
}
// e.g. getClass().getResourceAsStream("/" + FixtureReferences.REPORT)
```

#### Putting arbitrary strings into a Java file (`references`)

The same `<ProjectName>References` interface can also carry **arbitrary string constants** that
have nothing to do with bundled files — an endpoint URL, a schema version, an externally-supplied
image coordinate, anything. Declare them under `references { }`; each entry becomes a
`public static final String` named after the element in `UPPER_SNAKE_CASE`, sitting alongside any
bundled resource paths in the same generated interface. The value is a lazy `Property<String>`, so
it can be set from a provider (a task output, another project's resolved coordinate, …).

`references` takes an optional source-set name (like `importResourcesTask`), so constants can be
generated for a specific source set; the `main` set's interface is `<ProjectName>References` and
other sets append the capitalized name (e.g. `<ProjectName>ReferencesTest`).

```groovy
plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
group = 'com.example'

genericArtifacts {
    references {                                // declaring any reference generates the interface
        apiBaseUrl    { value = 'https://api.example.com' }   // -> FixtureReferences.API_BASE_URL
        schemaVersion { value 'v3' }                          // -> FixtureReferences.SCHEMA_VERSION
    }
    references('test') {
        stubUrl { value 'http://localhost:8080' }             // -> FixtureReferencesTest.STUB_URL
    }
}
```

This is the generic counterpart of the container plugin's `javaReference()`, which puts each
opted-in image's reference (its digest-pinned coordinate, read from the reference file) into its
`<ProjectName>Images` interface — both flow through the same `GenerateReferencesTask`. Each plugin
generates its own domain-named interface (`Images`/`Charts`/`References`), so the three are
non-colliding and can be applied side by side in one project.

##### Capturing a file's contents (`fromFile`)

Instead of a literal `value`, a reference can capture the **contents of a text file** with
`fromFile(<notation>)`. `notation` is anything a `FileCollection` accepts — a `Provider<RegularFile>`,
a `File`, a task output, or another element's resolved `files` — and its build dependencies are
carried, so the producing/resolving task runs first. The file is read lazily when the interface is
generated. A single-line file has its trailing newline dropped (so an image coordinate is clean); a
multi-line document is preserved verbatim — trailing newline and all — and emitted as a **Java text
block** (a multiline string), so multi-line documents stay readable.

The motivating case is capturing a built image's coordinate from another project. The container
plugin publishes each image's reference as a text artifact (classifier `<image>-reference`); resolve
it with `consume` and read it with `fromFile`:

```groovy
plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
group = 'com.example'

genericArtifacts {
    consume    { appRef   { from project(':app'); classifier = 'app-reference' } }
    references { appImage { fromFile genericArtifacts.consume.appRef.files } }
}
// -> public static final String APP_IMAGE =
//        AppImageReferencesLoader.load("APP_IMAGE", "registry.example.com/app:1.0@sha256:…");
//    (the reference is a single line: name:tag with the digest appended in place by default)
```

`fromFile` is fully generic: point it at any text document (a generated manifest, a license, a
descriptor) and its contents land in a Java constant.

##### Overriding generated values at runtime

Every generated constant resolves its value **at class-init time**, falling back to the generated
default if nothing overrides it — so a deployment can redirect an image coordinate, endpoint, or
resource path without regenerating or recompiling. The value is no longer a compile-time constant
(so it can't be used in a `switch` label or annotation), but it is read live. Each override is a
`key=value` entry where the **key is the constant name** (e.g. `APP_IMAGE`), scoped per interface.

Overrides come from two sources, both named after the **fully-qualified interface name** (for
`com.example.FixtureReferences`, the default package is just `FixtureReferences`):

- **Classpath resources** — every resource named `<qualified-interface>.properties` (a flat,
  dot-named file at the classpath root, e.g. `com.example.FixtureReferences.properties`). All such
  resources on the classpath are consulted, so several jars can each ship one; when more than one
  defines the same key, the one **earlier on the classpath wins**. These are the defaults an
  artifact bundles in its jar.
- **System-property files** — a comma-separated list of filesystem paths in the JVM system property
  `<qualified-interface>.overrides`. The **last file in the list wins**. These are operator
  overrides supplied at deploy time.

**Precedence (highest → lowest): system-property files (last in the list) > classpath resources
(earlier on the classpath) > the generated default.** So an external file always beats a bundled
resource, which beats the baked-in value.

```properties
# com.example.FixtureReferences.properties (bundled in the jar) or a file passed via -D…overrides
APP_IMAGE=registry.internal/app:2.0@sha256:…
```

```sh
# point one or more external files at the interface (later files override earlier ones)
java -Dcom.example.FixtureReferences.overrides=/etc/app/refs.properties,/etc/app/local.properties -jar app.jar
```

A missing file or an absent system property is ignored silently; an I/O error or a malformed
properties file (e.g. a bad `\uXXXX` escape) on any one source is logged via `System.Logger` (logger
name = the qualified interface name) and that source is skipped — the default still applies, the
application does not fail.

#### Application & distribution archives

The `application` and `distribution` plugins do **not** expose their `.zip`/`.tar`
archives as attribute-selectable variants — the archives sit only in the legacy,
attribute-less `archives` configuration (mixed in with the jar), so there is no native
attribute (and thus no preset) to target them by. The clean, composite-safe way to share
a distribution is to publish it as a generic artifact: the `distZip`/`distTar` tasks
expose a `Provider<RegularFile>` that already carries their build dependency, so it's a
one-liner with the existing `produce` API:

```groovy
plugins { id 'application'; id 'io.github.nhwalker.artifacts' }

genericArtifacts {
    produce {
        dist { classifier = 'dist'; artifact tasks.distZip.archiveFile }
    }
}
```

Consumers then select it with the normal API — `consume { theDist { from project(':app'); classifier = 'dist' } }` —
composite-safe and publishable, and `classifier = 'dist'` cleanly picks it even though
the module also publishes the usual `apiElements`/`runtimeElements` JVM variants.

### Consuming artifacts

Declare dependencies under `genericArtifacts { consume { } }`. Each element exposes the
resolved files as `…consume.<name>.files`, ready to wire into any task. The request
carries **exactly the attributes you declare — nothing by default** — and crucially it
does *not* add the `ecosystem` fence on the consumer side. That makes one API able to
fetch four different kinds of thing:

```groovy
genericArtifacts {
    consume {
        // 1. A generic artifact published by this plugin (project / composite / repo).
        //    `classifier` adds io.github.nhwalker.artifacts.classifier, which uniquely
        //    selects our variant even when the target also publishes JVM variants.
        theReport { from 'com.example:platform:1.0'; classifier = 'report' }

        // 2. A native variant of ANOTHER Gradle project — e.g. its sources jar.
        //    sources()/javadoc() are presets; or use attribute '...','...' for any
        //    other native variant (String values match typed producer attributes by name).
        libSources { from project(':lib'); sources() }

        // 3. The conventional default artifact of another project (a Java library's
        //    main jar) — no attributes needed.
        libJar { from project(':lib') }

        // 4. A plain Maven-repo artifact by classifier (artifact-only notation).
        guavaSources { from 'com.google.guava:guava:33.0.0-jre:sources@jar' }
    }
}

tasks.register('useReport') {
    def files = genericArtifacts.consume.theReport.files
    inputs.files files
    doLast { println files.singleFile.text }
}
```

**Why no `ecosystem` fence on the consumer?** The fence stays on the *producer* (so our
variants never leak into anyone's `runtimeClasspath`), but requiring it on the request
would wall the consumer off from everything else. Dropping it lets the *same* request
machinery — Gradle's variant matcher — select our artifacts (via `classifier`) or any
other project's variants (via their attributes), while keeping composite-build safety.

**Which mechanism for which source:**

| Source | How to consume | Notes |
|---|---|---|
| Our artifact, project / composite / repo | `classifier = '…'` (attribute) | composite-safe; uniquely selects our variant |
| Another project's native variant (sources, etc.) | `sources()` / `javadoc()` preset, or `attribute '…', '…'` | target must expose it as a variant |
| Another project's default artifact (main jar) | no attributes | falls back to the conventional default |
| Plain Maven-repo artifact (incl. sources jars) | classifier in the `from` notation (`:sources@jar`) | artifact-only; repo-only |

#### Staging resolved files on disk (`downloadTask` / `unpackTask`)

Often you just want the resolved artifact materialized in a directory. Each `consume`
entry can register a `Sync` task for that, defaulting the destination to
`build/inputs/<name>`:

- **`downloadTask`** — copies the resolved file(s) into the directory.
- **`unpackTask`** — extracts the resolved zip/tar archive(s) into the directory (the
  format is detected per file by extension).

The `{ }` closure configures the underlying `Sync` task — change the destination with
`into`, add `dependsOn`, etc. — and the method returns the `TaskProvider` so other tasks
can depend on it. The producing task is wired automatically, so the artifact is built/
resolved before staging.

```groovy
genericArtifacts {
    consume {
        theReport {
            from 'com.example:platform:1.0'; classifier = 'report'
            downloadTask()                                   // → build/inputs/theReport/
        }
        theDist {
            from 'com.example:app:1.0'; classifier = 'dist'
            unpackTask { into layout.buildDirectory.dir('app') }   // extract elsewhere
        }
    }
}

// returned TaskProvider, or wire the other way round inside the closure:
tasks.named('assembleSite') { dependsOn genericArtifacts.consume.theReport.downloadTask() }
```

Both methods are **idempotent**: the first call registers the task, and the no-arg
`downloadTask()` / `unpackTask()` thereafter return the *same* `TaskProvider` without
reconfiguring — so the accessor itself is the handle other tasks depend on, and it's safe
to call from inside another task's configuration block. Because a task is a file collection
of its outputs, consuming that output wires the dependency automatically (no `dependsOn`
needed):

```groovy
genericArtifacts {
    consume {
        theReport { from 'com.example:platform:1.0'; classifier = 'report'; downloadTask() }
    }
}

// BEST — consume the staged output; the dependency on downloadTheReport is inferred
tasks.register('publishSite', Copy) {
    from genericArtifacts.consume.theReport.downloadTask()
    into layout.buildDirectory.dir('site')
}

// typed DirectoryProperty input (also carries the dependency)
tasks.register('process', MyTask) {
    inputDir.fileProvider(genericArtifacts.consume.theReport.downloadTask().map { it.destinationDir })
}

// pure ordering, when the task doesn't take the files as input
tasks.named('check') { dependsOn genericArtifacts.consume.theReport.downloadTask() }
```

Use the `downloadTask { … }` / `unpackTask { … }` overload (with a closure) to configure
the `Sync` from a normal context such as the `consume` block; use the no-arg form as the
handle elsewhere.

### Composite builds

Because identity is the project coordinate and selection is an attribute, an included
build transparently substitutes the external coordinate — the consumer build script is
**identical** whether the artifact resolves from a repository or a local project:

```groovy
// consumer settings.gradle
includeBuild '../platform'
// consumer build.gradle — unchanged from the repo-resolved case
genericArtifacts { consume { theReport { from 'com.example:platform:1.0'; classifier = 'report' } } }
```

### Low-level plumbing

The DSL is built on public helpers in
`io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies`
(`registerSchema`, `registerAttributeKey`, `elements`, `dependencyBucket`,
`resolvable`), so you can wire your own configurations into the same model without the
`genericArtifacts { }` extension.

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
