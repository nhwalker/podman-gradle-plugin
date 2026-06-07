package io.github.nhwalker.helm.gradle.dependency;

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsAttributes;

/**
 * The well-known attribute keys and values describing helm chart variants.
 *
 * <p>Charts are published and consumed as <em>generic artifacts</em> (see
 * {@link io.github.nhwalker.artifacts.gradle.dependency.ArtifactsDependencies}): module
 * <em>identity</em> stays at the Gradle project's implicit {@code group:name} coordinate
 * (its default capability), the required {@link ArtifactsAttributes#ECOSYSTEM
 * ecosystem=generic-artifact} marker fences the variants off from the JVM ecosystem, and
 * the {@link ArtifactsAttributes#CLASSIFIER classifier} attribute (defaulting the
 * variant's Maven classifier too) plus the free String attributes below refine
 * <em>which chart</em> ({@link #CHART_NAME_KEY}) and <em>which form</em>
 * ({@link #CHART_TYPE_KEY}) a request selects.
 *
 * <p>The keys keep the {@code io.github.nhwalker.helm.*} namespace so they never collide
 * with the generic core attributes and so a published module's Gradle Module Metadata
 * advertises stable, helm-specific attribute names.
 */
public final class HelmAttributes {

    private HelmAttributes() {
    }

    /** Free-attribute key selecting which chart (by name) within a module that publishes several. */
    public static final String CHART_NAME_KEY = "io.github.nhwalker.helm.chartName";

    /**
     * Free-attribute key distinguishing the form of the chart artifact. Today the only
     * form is the packaged archive ({@link #CHART_TYPE_PACKAGE}); the attribute exists so
     * other forms (for example raw chart sources) can be added without breaking consumers.
     */
    public static final String CHART_TYPE_KEY = "io.github.nhwalker.helm.chartType";

    /** {@link #CHART_TYPE_KEY} value for the packaged {@code .tgz} archive. */
    public static final String CHART_TYPE_PACKAGE = "package";
}
