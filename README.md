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
- **Plugin id:** `io.github.nhwalker.podman`

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
PodmanPlugin                 ← entry point; registers extension + wires conventions
   │
   ├── PodmanExtension       ← the `podman { }` block (shared defaults)
   │
   └── AbstractPodmanTask    ← the engine: assembles + executes the command
          ├── PodmanBuildTask
          ├── PodmanPushTask
          ├── PodmanPullTask
          ├── PodmanTagTask
          ├── PodmanRunTask
          ├── PodmanStopTask
          ├── PodmanRemoveContainerTask
          ├── PodmanRemoveImageTask
          ├── PodmanSaveTask
          ├── PodmanLoadTask
          ├── PodmanCopyFromImageTask  ← create + cp + rm orchestration
          └── PodmanExecTask  ← generic escape hatch
```

### 1. `PodmanPlugin` — the entry point

When you apply `id 'io.github.nhwalker.podman'`, the plugin's `apply(Project)`
does two small things:

```java
public void apply(Project project) {
    // (a) register the `podman { }` extension and give `executable` a default
    PodmanExtension extension =
            project.getExtensions().create("podman", PodmanExtension.class);
    extension.getExecutable().convention("podman");

    // (b) for EVERY podman task that ever gets created, inherit the extension's
    //     values as conventions and put the task in the "podman" group
    project.getTasks().withType(AbstractPodmanTask.class).configureEach(task -> {
        task.setGroup("podman");
        task.getExecutable().convention(extension.getExecutable());
        task.getGlobalOptions().convention(extension.getGlobalOptions());
        task.getConnection().convention(extension.getConnection());
    });
}
```

The key mechanism is `tasks.withType(...).configureEach(...)`. This is a *live*,
lazy hook: it runs its configuration block for any current **or future** task of
type `AbstractPodmanTask`, without forcing those tasks to be created. That is how
a value you set once in `podman { }` reaches every podman task — even tasks
registered after the `podman { }` block — while staying compatible with lazy task
realization.

Because these are `convention(...)` calls (not `set(...)`), they only supply a
*default*. Anything you set explicitly on an individual task overrides the
extension.

### 2. `PodmanExtension` — shared defaults

The extension is an abstract class with three lazy properties, so Gradle
generates the implementation:

| Property        | Type                   | Meaning                                                            |
|-----------------|------------------------|--------------------------------------------------------------------|
| `executable`    | `Property<String>`     | Which binary to run. Defaults to `podman` (resolved on `PATH`).    |
| `globalOptions` | `ListProperty<String>` | Flags inserted *before* the subcommand on every call. Empty by default. |
| `connection`    | `Property<String>`     | Optional `--connection <name>` for a remote podman service.        |

These exist purely to be wired into tasks as conventions (see above).

### 3. `AbstractPodmanTask` — the engine

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
the helpers. For example, `PodmanBuildTask.buildSubcommand()` does roughly:

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
`io.github.nhwalker.podman.gradle.tasks`.

| Task type                    | Command   | Notable typed properties                                                                 |
|------------------------------|-----------|------------------------------------------------------------------------------------------|
| `PodmanBuildTask`            | `build`   | `contextDirectory`, `containerfile`, `tags`, `buildArgs`, `labels`, `platform`, `target`, `noCache`, `pull` |
| `PodmanPushTask`             | `push`    | `image`, `destination`, `tlsVerify`                                                       |
| `PodmanPullTask`             | `pull`    | `image`, `platform`, `tlsVerify`                                                          |
| `PodmanTagTask`              | `tag`     | `sourceImage`, `targetImages` (runs once per target — see below)                         |
| `PodmanRunTask`              | `run`     | `image`, `containerName`, `detach`, `remove`, `tty`, `interactive`, `ports`, `volumes`, `environment`, `command` |
| `PodmanStopTask`             | `stop`    | `containers`, `all`, `stopTimeout`                                                        |
| `PodmanRemoveContainerTask`  | `rm`      | `containers`, `force`, `volumes`, `all`                                                   |
| `PodmanRemoveImageTask`      | `rmi`     | `images`, `force`, `all`                                                                  |
| `PodmanSaveTask`             | `save`    | `image`, `outputFile`, `format`                                                           |
| `PodmanLoadTask`             | `load`    | `inputFile`                                                                               |
| `PodmanCopyFromImageTask`    | `create` + `cp` + `rm` | `image` *or* `container`, `paths`, `createOptions`, `copyOptions`, `removeContainer` |
| `PodmanExecTask`             | *any*     | `arguments`                                                                               |

### Tasks that issue more than one podman command

Most tasks run exactly one podman invocation. Two orchestrate several, building
on the shared `runSubcommand(...)` primitive in `AbstractPodmanTask` (which still
honors `dryRun`/`ignoreExitValue` for every call):

- **`PodmanTagTask`** — `podman tag` only accepts a single new name per
  invocation, so the task runs `podman tag <source> <target>` **once per entry**
  in `targetImages`.

- **`PodmanCopyFromImageTask`** — `podman cp` operates on *containers*, not
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
- **`PodmanBuildTask.contextDirectory` is `@Internal`**, not an input. The build
  context can be huge (and podman/BuildKit do their own change detection), so the
  plugin deliberately does not snapshot it.
- **`PodmanSaveTask` declares its archive as `@OutputFile`** and `PodmanLoadTask`
  declares its `@InputFile`. These produce/consume real files, so they *do*
  participate in up-to-date checks and can be skipped when nothing changed.

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
  `getProject()` — `PodmanBuildTask`'s constructor, to default the context
  directory — runs at *configuration* time, which is allowed.)
- **All inputs are `Provider`-backed**, so their values are resolved lazily and
  serialized into the configuration-cache entry rather than recomputed against a
  live project model.

A functional test (`PodmanPluginFunctionalSpec`) runs a task twice with
`--configuration-cache` and asserts the second run reports
`Reusing configuration cache.`

---

## Usage

Apply the plugin:

```groovy
plugins {
    id 'io.github.nhwalker.podman' version '0.1.0'
}
```

Configure shared defaults (optional):

```groovy
podman {
    executable    = '/usr/local/bin/podman'   // default: 'podman' on PATH
    globalOptions = ['--log-level', 'info']    // inserted before every subcommand
    connection    = 'my-remote'                // adds --connection my-remote
}
```

Declare the tasks you need:

```groovy
import io.github.nhwalker.podman.gradle.tasks.*

tasks.register('buildImage', PodmanBuildTask) {
    contextDirectory = layout.projectDirectory.dir('src/main/docker')
    containerfile    = layout.projectDirectory.file('src/main/docker/Containerfile')
    tags             = ["example/app:${version}", 'example/app:latest']
    buildArgs        = ['VERSION': version.toString()]
    labels           = ['org.opencontainers.image.source': 'https://example.com/repo']
    platform         = 'linux/amd64'
    pull             = true
}

tasks.register('tagImage', PodmanTagTask) {
    sourceImage  = "example/app:${version}"
    targetImages = ["registry.example.com/example/app:${version}"]
}

tasks.register('pushImage', PodmanPushTask) {
    dependsOn 'tagImage'
    image     = "registry.example.com/example/app:${version}"
    tlsVerify = true
}

tasks.register('runApp', PodmanRunTask) {
    image         = 'example/app:latest'
    containerName = 'app'
    detach        = true
    remove        = true
    ports         = ['8080:8080']
    environment   = ['SPRING_PROFILES_ACTIVE': 'dev']
    command       = ['--server.port=8080']
}

tasks.register('stopApp', PodmanStopTask) {
    containers      = ['app']
    ignoreExitValue = true   // tolerate "no such container"
}

tasks.register('saveImage', PodmanSaveTask) {
    image      = 'example/app:latest'
    outputFile = layout.buildDirectory.file('images/app.tar')
    format     = 'oci-archive'
}

tasks.register('loadImage', PodmanLoadTask) {
    inputFile = layout.buildDirectory.file('images/app.tar')
}
```

### Copying files out of an image

Extract build artifacts or config from an image without running it. The task
creates a throwaway container from the image, copies each path out, and removes
the container automatically:

```groovy
tasks.register('extractArtifacts', PodmanCopyFromImageTask) {
    image = 'example/app:latest'
    paths = [
        // path inside the image : destination on the host
        '/app/app.jar'    : layout.buildDirectory.file('extracted/app.jar').get().asFile.path,
        '/etc/app/config' : layout.buildDirectory.dir('extracted/config').get().asFile.path,
    ]
    copyOptions = ['--archive']   // preserve uid/gid/permissions (optional)
}

// Or copy from a container that already exists (no create/remove happens):
tasks.register('extractFromRunning', PodmanCopyFromImageTask) {
    container = 'app'
    paths = ['/var/log/app.log': layout.buildDirectory.file('logs/app.log').get().asFile.path]
}
```

### The generic escape hatch

For any subcommand without a dedicated task, use `PodmanExecTask`, optionally
capturing its output:

```groovy
tasks.register('listImages', PodmanExecTask) {
    arguments     = ['images', '--format', '{{.Repository}}:{{.Tag}}']
    captureOutput = true
    doLast { println standardOutput }
}
```

### Inspecting a command without running it

```groovy
tasks.register('buildImage', PodmanBuildTask) {
    tags   = ['example/app:latest']
    dryRun = true   // prints "[dry-run] podman build -t example/app:latest ." and exits
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
