package io.github.nhwalker.helm.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * Functional tests for cross-project chart dependencies: a consumer chart that
 * depends on a producer chart has the producer's packaged archive staged into its
 * own {@code charts/} directory, with the producer built first.
 *
 * A fake `helm` script logs every invocation and, for `package`, writes a .tgz
 * into the requested destination, so the full resolution + execution path runs
 * without helm.
 */
class HelmChartGraphFunctionalSpec extends Specification {

    @TempDir
    File dir

    File argsLog

    private File fakeHelm(File root) {
        def bin = new File(root, 'fake-helm')
        bin << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
if [ "\$1" = "package" ]; then
  dest="."
  prev=""
  for a in "\$@"; do
    if [ "\$prev" = "--destination" ]; then dest="\$a"; fi
    prev="\$a"
  done
  mkdir -p "\$dest"
  : > "\$dest/chart-0.0.0.tgz"
fi
exit 0
"""
        bin.setExecutable(true)
        return bin
    }

    private void writeChart(File projectDir, String dir, String name) {
        def chartDir = new File(projectDir, dir)
        chartDir.mkdirs()
        new File(chartDir, 'Chart.yaml') << "apiVersion: v2\nname: ${name}\nversion: 0.0.0\n"
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
                .withProjectDir(dir)
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput()
    }

    def setup() {
        argsLog = new File(dir, 'helm-args.log')
    }

    def "stages a producer chart into the consumer's charts directory, producer first"() {
        given:
        def fake = fakeHelm(dir)
        new File(dir, 'settings.gradle') << "rootProject.name='graph'\ninclude ':base', ':umbrella'\n"

        new File(dir, 'base').mkdirs()
        writeChart(new File(dir, 'base'), 'src/main/helm/base', 'base')
        new File(dir, 'base/build.gradle') << """
            plugins { id 'io.github.nhwalker.helm' }
            helm {
                executable = '${fake.absolutePath}'
                charts { base { } }
            }
        """

        new File(dir, 'umbrella').mkdirs()
        writeChart(new File(dir, 'umbrella'), 'src/main/helm/umbrella', 'umbrella')
        new File(dir, 'umbrella/build.gradle') << """
            plugins { id 'io.github.nhwalker.helm' }
            helm {
                executable = '${fake.absolutePath}'
                charts { umbrella { from project(':base') } }
            }
        """

        when:
        def result = runner(':umbrella:packageUmbrellaChart').build()

        then: 'the producer was packaged before the consumer was staged'
        result.task(':base:packageBaseChart').outcome == SUCCESS
        result.task(':umbrella:stageUmbrellaChart').outcome == SUCCESS
        result.task(':umbrella:packageUmbrellaChart').outcome == SUCCESS
        def order = result.tasks*.path
        order.indexOf(':base:packageBaseChart') < order.indexOf(':umbrella:stageUmbrellaChart')

        and: 'the producer archive was staged into the consumer chart under charts/'
        new File(dir, 'umbrella/build/helm/umbrella/staged/charts/base.tgz').isFile()
    }

    def "the chart graph is configuration-cache compatible"() {
        given:
        def fake = fakeHelm(dir)
        new File(dir, 'settings.gradle') << "rootProject.name='graph'\ninclude ':base', ':umbrella'\n"
        new File(dir, 'base').mkdirs()
        writeChart(new File(dir, 'base'), 'src/main/helm/base', 'base')
        new File(dir, 'base/build.gradle') << """
            plugins { id 'io.github.nhwalker.helm' }
            helm { executable = '${fake.absolutePath}'; charts { base { } } }
        """
        new File(dir, 'umbrella').mkdirs()
        writeChart(new File(dir, 'umbrella'), 'src/main/helm/umbrella', 'umbrella')
        new File(dir, 'umbrella/build.gradle') << """
            plugins { id 'io.github.nhwalker.helm' }
            helm { executable = '${fake.absolutePath}'
                   charts { umbrella { from project(':base') } } }
        """

        when:
        runner(':umbrella:packageUmbrellaChart', '--configuration-cache').build()
        def result = runner(':umbrella:packageUmbrellaChart', '--configuration-cache').build()

        then:
        result.output.contains('Reusing configuration cache.')
        result.task(':umbrella:packageUmbrellaChart').outcome in [SUCCESS, UP_TO_DATE]
    }
}
