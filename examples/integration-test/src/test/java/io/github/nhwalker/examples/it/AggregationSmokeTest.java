package io.github.nhwalker.examples.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * Build-graph-only smoke test for the aggregation.
 *
 * <p>It asserts that the image references and packaged chart aggregated from the sibling
 * projects are available to test code through the generated {@code IntegrationRefsTest}
 * interface (the {@code test} source set suffixes the class name with {@code Test}) and
 * the bundled test resources. The constants are exactly what a Testcontainers-based test
 * would feed to {@code new GenericContainer<>(...)} — the commented blocks show where.
 */
class AggregationSmokeTest {

    @Test
    void aggregatesApiImageReference() {
        String api = IntegrationRefsTest.API_IMAGE;
        assertTrue(api.startsWith("registry.example.com/api:1.0"), api);
        // includeDigest defaults to true, so the coordinate is digest-pinned when podman
        // can resolve a digest for the locally built image.
        if (api.contains("@")) {
            assertTrue(api.contains("@sha256:"), api);
        }

        // A real Testcontainers test would start the aggregated image here:
        // try (GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(api))) {
        //     c.start();
        //     // ... assert against the running container ...
        // }
    }

    @Test
    void aggregatesWorkerImageReference() {
        String worker = IntegrationRefsTest.WORKER_IMAGE;
        assertTrue(worker.startsWith("registry.example.com/worker:1.0"), worker);
    }

    @Test
    void bundlesAggregatedPlatformChart() throws Exception {
        String path = IntegrationRefsTest.PLATFORM_CHART_RESOURCE;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "platform chart should be bundled on the test classpath at " + path);
        }

        // A real deployment test would extract/install this chart, e.g. via helm against
        // a kind/k3s cluster, pointing its image values at the aggregated references above.
    }
}
