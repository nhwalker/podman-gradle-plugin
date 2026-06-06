package io.github.nhwalker.helm.gradle.dependency;

import org.gradle.api.attributes.Attribute;

/**
 * The custom Gradle {@link Attribute}s used to describe helm chart variants in
 * variant-aware dependency resolution, plus their well-known values.
 *
 * <p>These live in a dedicated namespace (rather than reusing the JVM
 * {@code Usage}/{@code Category}/{@code LibraryElements} attributes) so helm chart
 * variants can never accidentally match Java ecosystem variants. The
 * {@link #ECOSYSTEM} attribute, required on every helm variant and request, is the
 * structural fence: no JVM variant declares it.
 *
 * <p>Module <em>identity</em> stays at the Gradle project's implicit
 * {@code group:name} coordinate (its default capability); <em>which chart</em>
 * inside a module is chosen by {@link #CHART_NAME} and <em>which form</em> by
 * {@link #CHART_TYPE} — the same "one module, several attribute-selected variants"
 * pattern the Java plugin uses for its sources and javadoc jars.
 */
public final class HelmAttributes {

    private HelmAttributes() {
    }

    /**
     * Isolation marker carried by every helm variant and every helm request. Its
     * sole value is {@link #ECOSYSTEM_VALUE}.
     */
    public static final Attribute<String> ECOSYSTEM =
            Attribute.of("io.github.nhwalker.helm.ecosystem", String.class);

    /** Selects which chart (by name) within a module that publishes several. */
    public static final Attribute<String> CHART_NAME =
            Attribute.of("io.github.nhwalker.helm.chartName", String.class);

    /**
     * Distinguishes the form of the chart artifact. Today the only form is the
     * packaged archive ({@link #CHART_TYPE_PACKAGE}); the attribute exists so other
     * forms (for example raw chart sources) can be added without breaking consumers.
     */
    public static final Attribute<String> CHART_TYPE =
            Attribute.of("io.github.nhwalker.helm.chartType", String.class);

    /** The only valid value of {@link #ECOSYSTEM}. */
    public static final String ECOSYSTEM_VALUE = "helm-chart";

    /** {@link #CHART_TYPE} value for the packaged {@code .tgz} archive. */
    public static final String CHART_TYPE_PACKAGE = "package";
}
