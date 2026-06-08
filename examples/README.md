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

## Projects and the features they show

| Project | Plugin | Demonstrates |
| --- | --- | --- |
| `base-image` | container | tags, labels, build args, exported archive variant (`createArchive`), `maven-publish` of `components.container` |
| `api-service` | container | cross-project base image (`from 'BASE_IMAGE', project(':base-image')`), platform, `javaReference()` → `ApiImages` constants consumed by Java |
| `worker-service` | container | intra-project sibling base image (`from 'BASE_IMAGE', images.runtime`), `noCache`/`pull` |
| `base-chart` | helm | minimal chart, lint, package, `maven-publish` of `components.helm` |
| `platform-chart` | helm | subchart aggregation (`from project(':base-chart')`), `preValues` substitution, version overrides, `importResourcesTask()` → `PlatformCharts` |
| `reports` | artifacts | `produce`/`consume`, `downloadTask`/`unpackTask`, `importResourcesTask`/`importUnpackedResourcesTask`, `references` → `ReportRefs` (no podman/helm needed) |
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
