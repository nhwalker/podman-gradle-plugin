# podman-gradle-plugin

A standalone Gradle plugin that contributes tasks for working with
[Podman](https://podman.io/) in a Gradle build.

The plugin shells out to the `podman` command line using Gradle's
[`ExecOperations`](https://docs.gradle.org/current/javadoc/org/gradle/process/ExecOperations.html)
— it never talks to a Docker/Podman socket or REST API. If `podman` runs from
your shell, it runs from this plugin.

- **Implementation language:** Java
- **Build scripts:** Groovy
- **Built/tested with:** Gradle 9.2, Java 17+

## Applying the plugin

```groovy
plugins {
    id 'io.github.nhwalker.podman' version '0.1.0'
}
```

The plugin registers a `podman { }` extension and applies its values as
conventions to every podman task. It deliberately registers **no** tasks of its
own — you declare the ones you need.

## Configuration

```groovy
podman {
    // Executable to invoke; defaults to 'podman' on the PATH.
    executable = '/usr/local/bin/podman'

    // Global options inserted before every subcommand.
    globalOptions = ['--log-level', 'info']

    // Optional remote connection: adds `--connection <name>`.
    connection = 'my-remote'
}
```

Every task below also accepts these shared properties directly (overriding the
extension), plus:

| Property          | Default | Meaning                                              |
|-------------------|---------|------------------------------------------------------|
| `ignoreExitValue` | `false` | Don't fail the task on a non-zero podman exit code.  |
| `dryRun`          | `false` | Log the assembled command and skip execution.        |
| `captureOutput`   | `false` | Capture stdout (see `task.standardOutput`) instead of streaming it. |

## Tasks

All task types live in `io.github.nhwalker.podman.gradle.tasks`.

| Task type                    | podman command  |
|------------------------------|-----------------|
| `PodmanBuildTask`            | `podman build`  |
| `PodmanPushTask`             | `podman push`   |
| `PodmanPullTask`             | `podman pull`   |
| `PodmanTagTask`              | `podman tag`    |
| `PodmanRunTask`              | `podman run`    |
| `PodmanStopTask`             | `podman stop`   |
| `PodmanRemoveContainerTask`  | `podman rm`     |
| `PodmanRemoveImageTask`      | `podman rmi`    |
| `PodmanSaveTask`             | `podman save`   |
| `PodmanLoadTask`             | `podman load`   |
| `PodmanExecTask`             | any subcommand  |

### Build an image

```groovy
import io.github.nhwalker.podman.gradle.tasks.*

tasks.register('buildImage', PodmanBuildTask) {
    contextDirectory = layout.projectDirectory.dir('src/main/docker')
    containerfile = layout.projectDirectory.file('src/main/docker/Containerfile')
    tags = ["example/app:${version}", 'example/app:latest']
    buildArgs = ['VERSION': version.toString()]
    labels = ['org.opencontainers.image.source': 'https://example.com/repo']
    platform = 'linux/amd64'
    noCache = false
    pull = true
}
```

### Tag and push

```groovy
tasks.register('tagImage', PodmanTagTask) {
    sourceImage = "example/app:${version}"
    targetImages = ["registry.example.com/example/app:${version}"]
}

tasks.register('pushImage', PodmanPushTask) {
    dependsOn 'tagImage'
    image = "registry.example.com/example/app:${version}"
    tlsVerify = true
}
```

### Run, stop and clean up

```groovy
tasks.register('runApp', PodmanRunTask) {
    image = 'example/app:latest'
    containerName = 'app'
    detach = true
    remove = true
    ports = ['8080:8080']
    environment = ['SPRING_PROFILES_ACTIVE': 'dev']
    command = ['--server.port=8080']
}

tasks.register('stopApp', PodmanStopTask) {
    containers = ['app']
    ignoreExitValue = true   // tolerate "no such container"
}

tasks.register('cleanImage', PodmanRemoveImageTask) {
    images = ['example/app:latest']
    force = true
    ignoreExitValue = true
}
```

### Save / load an image archive

```groovy
tasks.register('saveImage', PodmanSaveTask) {
    image = 'example/app:latest'
    outputFile = layout.buildDirectory.file('images/app.tar')
    format = 'oci-archive'
}

tasks.register('loadImage', PodmanLoadTask) {
    inputFile = layout.buildDirectory.file('images/app.tar')
}
```

### Escape hatch: any subcommand

```groovy
tasks.register('listImages', PodmanExecTask) {
    arguments = ['images', '--format', '{{.Repository}}:{{.Tag}}']
    captureOutput = true
    doLast { println standardOutput }
}
```

## Notes

- Tasks that perform side effects in podman's storage (`build`, `run`, `push`,
  …) declare no Gradle outputs, so they run on every invocation by design.
  `PodmanSaveTask` declares its archive as an output and participates in
  up-to-date checks.
- The plugin is compatible with the Gradle
  [configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html).

## Building from source

```bash
./gradlew build
```

## License

Released into the public domain under the [Unlicense](LICENSE).
