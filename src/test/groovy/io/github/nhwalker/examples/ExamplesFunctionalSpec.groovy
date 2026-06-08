package io.github.nhwalker.examples

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Requires
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * Runs the real {@code examples/} build as part of the plugin's test suite, proving the
 * shipped examples actually work against the plugin under test.
 *
 * <p>The examples drive real {@code podman}/{@code helm}; cases that need them self-skip
 * via {@link Requires} when the binary is not on {@code PATH}, so this spec stays green in
 * CI without a container runtime (only the generic-artifacts example runs there). The
 * examples build wires the plugin via {@code includeBuild('..')} for standalone runs; here
 * we inject the plugin classpath with {@code withPluginClasspath()} and pass
 * {@code -PpluginUnderTest} so that {@code includeBuild} is skipped (avoiding contributing
 * the plugin twice).
 */
class ExamplesFunctionalSpec extends Specification {

    /** The examples build lives next to the plugin project (test working dir = project root). */
    static File examplesDir() {
        new File(System.getProperty('user.dir'), 'examples')
    }

    static boolean onPath(String exe) {
        String path = System.getenv('PATH')
        if (path == null) {
            return false
        }
        path.split(File.pathSeparator).any { dir -> new File(dir, exe).canExecute() }
    }

    static final boolean PODMAN = onPath('podman')
    static final boolean HELM = onPath('helm')

    private GradleRunner runner(String... args) {
        def all = (args as List) + '-PpluginUnderTest'
        GradleRunner.create()
                .withProjectDir(examplesDir())
                .withPluginClasspath()
                .withArguments(all as String[])
                .forwardOutput()
    }

    def "the generic-artifacts (reports) example builds without podman/helm"() {
        when:
        def result = runner(':reports:build', ':reports:downloadTheReport', ':reports:unpackTheBundle').build()

        then: 'produce, consume, references and resource bundling all run (or are up-to-date on rerun)'
        result.task(':reports:generateArtifactReferences').outcome in [SUCCESS, UP_TO_DATE]
        result.task(':reports:downloadTheReport').outcome in [SUCCESS, UP_TO_DATE]
        result.task(':reports:unpackTheBundle').outcome in [SUCCESS, UP_TO_DATE]
        result.task(':reports:jar').outcome in [SUCCESS, UP_TO_DATE]
    }

    def "the reports example builds with the configuration cache, then reuses it (no podman/helm)"() {
        given:
        runner(':reports:build', '--configuration-cache').build()

        when:
        def result = runner(':reports:build', '--configuration-cache').build()

        then:
        result.output.contains('Reusing configuration cache.')
        result.task(':reports:generateArtifactReferences').outcome in [SUCCESS, UP_TO_DATE]
    }

    @Requires({ ExamplesFunctionalSpec.PODMAN })
    def "the container examples build images, resolve the base-image graph, and generate the Images interface"() {
        when:
        def result = runner(':api-service:generateImageReferences', ':base-image:saveBaseImage').build()

        then: 'the base image is built before the app image, and the archive + Images interface are produced'
        result.task(':base-image:buildBaseImage').outcome == SUCCESS
        result.task(':api-service:buildApiImage').outcome == SUCCESS
        result.task(':api-service:generateImageReferences').outcome == SUCCESS
        result.task(':base-image:saveBaseImage').outcome == SUCCESS
    }

    @Requires({ ExamplesFunctionalSpec.HELM })
    def "the helm examples lint, package, and aggregate the base chart into the umbrella"() {
        when:
        def result = runner(':platform-chart:packagePlatformChart', ':platform-chart:lintPlatformChart').build()

        then: 'the subchart is packaged first and staged into the umbrella before it is packaged'
        result.task(':base-chart:packageBaseChart').outcome == SUCCESS
        result.task(':platform-chart:stagePlatformChart').outcome == SUCCESS
        result.task(':platform-chart:packagePlatformChart').outcome == SUCCESS
    }

    @Requires({ ExamplesFunctionalSpec.PODMAN && ExamplesFunctionalSpec.HELM })
    def "the integration-test example aggregates image references and the chart and runs its tests"() {
        when:
        def result = runner(':integration-test:test').build()

        then:
        result.task(':integration-test:test').outcome == SUCCESS
    }

    @Requires({ ExamplesFunctionalSpec.PODMAN && ExamplesFunctionalSpec.HELM })
    def "the integration-test example is configuration-cache compatible"() {
        given:
        runner(':integration-test:test', '--configuration-cache').build()

        when:
        def result = runner(':integration-test:test', '--configuration-cache').build()

        then:
        result.output.contains('Reusing configuration cache.')
        result.task(':integration-test:test').outcome in [SUCCESS, UP_TO_DATE]
    }
}
