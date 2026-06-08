# Examples

Runnable example projects for the `podman-gradle-plugin`, covering every major feature
of the three plugins (`io.github.nhwalker.container`, `io.github.nhwalker.helm`,
`io.github.nhwalker.artifacts`).

These examples are a self-contained composite build. They wire in the plugin from the
parent build via `includeBuild('..')`, so run them with the repository's Gradle wrapper:

```sh
# from the repository root
./gradlew -p examples <task>

# or from this directory
../gradlew <task>
```

They are also executed as part of the plugin's own test suite (see
`src/test/groovy/io/github/nhwalker/examples/ExamplesFunctionalSpec.groovy`), so they are
guaranteed to keep working against the plugin.

## Requirements

The examples drive the **real** `podman` and `helm` binaries.

- The `reports` example needs neither and always runs.
- The container examples need `podman` on `PATH`.
- The helm examples need `helm` on `PATH`.
- The `integration-test` example needs both.

Point at a non-`PATH` install with `-Ppodman.executable=/path/to/podman` and/or
`-Phelm.executable=/path/to/helm`.

> The `-PpluginUnderTest` flag is used only by the plugin's own test harness (it skips the
> `includeBuild('..')` so the harness can inject the plugin classpath instead). You do not
> need it for normal runs.

## Lifecycle integration

All three plugins apply Gradle's `lifecycle-base` plugin and, **by default**, wire their
work into the standard lifecycle tasks:

- `assemble` (and therefore `build`) builds every declared **container image** (and saves
  its archive when `createArchive` is on), packages every **helm chart**, and builds every
  **produced generic artifact**.
- `check` (and therefore `build`) lints every helm chart whose `lint` is on.

Because building images/charts drives the real `podman`/`helm` binaries, a whole-tree
`./gradlew -p examples build` needs **both** binaries. Run per-project (e.g.
`:reports:build`, which needs neither) to scope what gets built.

Opt out with the `lifecycleIntegration` flag — project-wide (default `true`) with a
per-item override:

```groovy
container {
    lifecycleIntegration = false            // no image is part of build for this project
    images {
        app { lifecycleIntegration = true } // ...except this one
    }
}
helm    { charts  { legacy { lifecycleIntegration = false } } }  // skip this chart
genericArtifacts { produce { huge { lifecycleIntegration = false } } }
```

The tasks always remain runnable by name (e.g. `./gradlew buildAppImage`,
`./gradlew lintLegacyChart`) regardless of this flag.

## Projects and the features they show

| Project | Plugin | Demonstrates |
| --- | --- | --- |
| `base-image` | container | tags, labels, build args, exported archive variant (`createArchive`), `maven-publish` of `components.genericArtifacts` |
| `api-service` | container | cross-project base image (`from 'BASE_IMAGE', project(':base-image')`), platform, `javaReference()` → `ApiImages` constants consumed by Java; `maven-publish` of `components.java` (jar + image in one module) |
| `worker-service` | container | intra-project sibling base image (`from 'BASE_IMAGE', images.runtime`), `noCache`/`pull` |
| `multi-image-archive` | container | bundles several images into ONE archive (`archives { }` → `podman save img1 img2`) with `pullPolicy`, published as a variant of `components.genericArtifacts` |
| `base-chart` | helm | minimal chart, lint, package, `maven-publish` of `components.genericArtifacts` |
| `platform-chart` | helm | subchart aggregation (`from project(':base-chart')`), `preValues` substitution, version overrides, `importResourcesTask()` → `PlatformCharts`; `maven-publish` of `components.java` (jar + chart in one module) |
| `reports` | artifacts | `produce`/`consume`, `downloadTask`/`unpackTask`, `importResourcesTask`/`importUnpackedResourcesTask`, `references` → `ReportRefs` (no podman/helm needed) |
| `combo` | container + helm + artifacts | ★ image + chart + generic artifact in ONE project, published as a single module via `from components.genericArtifacts`; chart marked `defaultArtifact` (the module's bare-GAV main artifact) |
| `integration-test` | artifacts | ★ aggregates image references + the packaged chart from the projects above for deployment / container testing |

## The `integration-test` aggregator

`integration-test` is the headline example: a downstream module that aggregates artifacts
from the other projects for deployment / container-based testing.

It consumes, by attribute/classifier:

- the digest-pinned image references of `api-service` and `worker-service`, turned into
  String constants on a generated `IntegrationRefs` interface (test source set), and
- the packaged `platform-chart` umbrella chart, bundled into its test resources at
  `charts/platform.tgz`.

`AggregationSmokeTest` asserts those constants/resources are present — which is exactly the
information a Testcontainers-based test would hand to `new GenericContainer<>(...)`. It is
build-graph-only (no Testcontainers dependency); the comments in the test mark where the
real container/deploy calls would go.

```sh
./gradlew -p examples :integration-test:test   # requires podman + helm
```

## The `multi-image-archive` bundler

`multi-image-archive` shows how to ship **several images in one archive** with the first-class
`container { archives { } }` block — a single `podman save img1 img2 …` bundle, as opposed to
the per-image `createArchive` (which exports one tar per image).

An archive bundles a combination of image references: sibling images, cross-project image
references (`from project(':…')`), published reference files, and arbitrary literal image
strings. Before saving, the task runs `podman pull --policy <pullPolicy>` (default `missing`)
over the members so anything not already in local storage is fetched. The resulting tar is
published as a variant of `components.genericArtifacts`, so a consumer pulls the whole bundle
with one dependency and `podman load`s it in one shot.

```sh
./gradlew -p examples :multi-image-archive:saveBundleArchive   # requires podman
./gradlew -p examples :multi-image-archive:publishMavenPublicationToLocalExamplesRepository
```
