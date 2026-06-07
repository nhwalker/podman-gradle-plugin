package io.github.nhwalker.helm.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * Functional tests using Gradle TestKit. A fake {@code helm} script records the
 * arguments it was invoked with (and, for {@code package}, writes an archive into
 * the requested destination), so the full task execution path runs without a real
 * helm binary.
 */
class HelmPluginFunctionalSpec extends Specification {

    @TempDir
    File testProjectDir

    File buildFile
    File argsLog
    File fakeBin

    def setup() {
        buildFile = new File(testProjectDir, 'build.gradle')
        new File(testProjectDir, 'settings.gradle') << "rootProject.name = 'fixture'\n"

        // A fake helm that appends its arguments to a log and, for `package`, drops
        // a .tgz into the directory named after --destination.
        argsLog = new File(testProjectDir, 'helm-args.log')
        fakeBin = new File(testProjectDir, 'fake-helm')
        fakeBin << """#!/usr/bin/env sh
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
        fakeBin.setExecutable(true)
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput()
    }

    private void writeChart(String dir, String name) {
        def chartDir = new File(testProjectDir, dir)
        chartDir.mkdirs()
        new File(chartDir, 'Chart.yaml') << "apiVersion: v2\nname: ${name}\nversion: 0.0.0\n"
    }

    def "runs a HelmExecTask against the configured executable"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.helm' }

            helm {
                executable = '${fakeBin.absolutePath}'
                globalOptions = ['--namespace', 'platform']
            }

            tasks.register('helmVersion', io.github.nhwalker.helm.gradle.tasks.HelmExecTask) {
                arguments = ['version', '--short']
            }
        """

        when:
        def result = runner('helmVersion').build()

        then:
        result.task(':helmVersion').outcome == SUCCESS
        argsLog.text.trim() == '--namespace platform version --short'
    }

    def "packages a declared chart into a stable archive path"() {
        given:
        writeChart('src/main/helm/api', 'api')
        buildFile << """
            plugins { id 'io.github.nhwalker.helm' }

            helm {
                executable = '${fakeBin.absolutePath}'
                charts { api { chartVersion = '1.0.0' } }
            }
        """

        when:
        def result = runner('packageApiChart').build()

        then:
        result.task(':stageApiChart').outcome == SUCCESS
        result.task(':packageApiChart').outcome == SUCCESS
        new File(testProjectDir, 'build/helm/api/api.tgz').isFile()
        argsLog.readLines().any { it.startsWith('package ') && it.contains('--version 1.0.0') }
    }

    def "injects build-time pre-values into Chart.yaml and values.yaml"() {
        given:
        def chartDir = new File(testProjectDir, 'src/main/helm/api')
        chartDir.mkdirs()
        // Mixed whitespace, plus an unset placeholder that must be left untouched.
        new File(chartDir, 'Chart.yaml') << '''apiVersion: v2
name: api
version: {{ .PreValues.ChartVersion }}
appVersion: "{{.PreValues.AppVersion}}"
'''
        new File(chartDir, 'values.yaml') << '''image:
  tag: {{ .PreValues.AppTag }}
unset: {{ .PreValues.Missing }}
'''
        buildFile << """
            plugins { id 'io.github.nhwalker.helm' }

            helm {
                executable = '${fakeBin.absolutePath}'
                charts {
                    api {
                        preValues = ['ChartVersion': '9.9.9', 'AppVersion': '1.0', 'AppTag': 'sha-abc']
                    }
                }
            }
        """

        when:
        def result = runner('packageApiChart').build()

        then:
        result.task(':stageApiChart').outcome == SUCCESS
        result.task(':packageApiChart').outcome == SUCCESS

        and: 'placeholders are replaced (whitespace inside the braces ignored)'
        def stagedChart = new File(testProjectDir, 'build/helm/api/staged/Chart.yaml').text
        def stagedValues = new File(testProjectDir, 'build/helm/api/staged/values.yaml').text
        stagedChart.contains('version: 9.9.9')
        stagedChart.contains('appVersion: "1.0"')
        stagedValues.contains('tag: sha-abc')

        and: 'an unset placeholder is left untouched'
        stagedValues.contains('{{ .PreValues.Missing }}')

        and: 'the source chart is not modified'
        new File(chartDir, 'Chart.yaml').text.contains('{{ .PreValues.ChartVersion }}')
    }

    def "lints a declared chart"() {
        given:
        writeChart('src/main/helm/api', 'api')
        buildFile << """
            plugins { id 'io.github.nhwalker.helm' }

            helm {
                executable = '${fakeBin.absolutePath}'
                charts { api { } }
            }
        """

        when:
        def result = runner('lintApiChart').build()

        then:
        result.task(':lintApiChart').outcome == SUCCESS
        argsLog.readLines().any { it.startsWith('lint ') }
    }

    def "dryRun prints the command without invoking helm"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.helm' }

            helm { executable = '${fakeBin.absolutePath}' }

            tasks.register('lintAdhoc', io.github.nhwalker.helm.gradle.tasks.HelmLintTask) {
                chartDirectory = layout.projectDirectory.dir('chart')
                dryRun = true
            }
        """
        new File(testProjectDir, 'chart').mkdirs()

        when:
        def result = runner('lintAdhoc').build()

        then:
        result.task(':lintAdhoc').outcome == SUCCESS
        result.output.contains('[dry-run]')
        result.output.contains('lint ')
        !argsLog.exists()
    }

    def "package dryRun logs the command and touches nothing"() {
        given:
        writeChart('src/main/helm/api', 'api')
        buildFile << """
            plugins { id 'io.github.nhwalker.helm' }

            helm {
                executable = '${fakeBin.absolutePath}'
                charts { api { lint = false } }
            }
            tasks.withType(io.github.nhwalker.helm.gradle.tasks.HelmPackageTask).configureEach { dryRun = true }
        """

        when:
        def result = runner('packageApiChart').build()

        then: 'the command is printed, helm is never invoked, and no archive is produced'
        result.task(':packageApiChart').outcome == SUCCESS
        result.output.contains('[dry-run]')
        result.output.contains('package ')
        !argsLog.exists()
        !new File(testProjectDir, 'build/helm/api/api.tgz').exists()
    }

    def "is compatible with the configuration cache"() {
        given:
        writeChart('src/main/helm/api', 'api')
        buildFile << """
            plugins { id 'io.github.nhwalker.helm' }

            helm {
                executable = '${fakeBin.absolutePath}'
                charts { api { } }
            }
        """

        when:
        runner('packageApiChart', '--configuration-cache').build()
        def result = runner('packageApiChart', '--configuration-cache').build()

        then:
        result.output.contains('Reusing configuration cache.')
        result.task(':packageApiChart').outcome in [SUCCESS, UP_TO_DATE]
    }
}
