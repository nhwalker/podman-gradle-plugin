package io.github.nhwalker.podman.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Functional tests using Gradle TestKit. A fake {@code podman} script on the
 * PATH records the arguments it was invoked with, so the full task execution
 * path (injection, exec, exit handling) is exercised without a real podman.
 */
class PodmanPluginFunctionalSpec extends Specification {

    @TempDir
    File testProjectDir

    File buildFile
    File argsLog
    File fakeBin

    def setup() {
        buildFile = new File(testProjectDir, 'build.gradle')
        new File(testProjectDir, 'settings.gradle') << "rootProject.name = 'fixture'\n"

        // A fake podman that appends its arguments to a log and exits 0.
        argsLog = new File(testProjectDir, 'podman-args.log')
        fakeBin = new File(testProjectDir, 'fake-podman')
        fakeBin << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
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

    def "runs a PodmanExecTask against the configured executable"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.podman' }

            podman {
                executable = '${fakeBin.absolutePath}'
                globalOptions = ['--log-level', 'info']
            }

            tasks.register('listImages', io.github.nhwalker.podman.gradle.tasks.PodmanExecTask) {
                arguments = ['images', '-a']
            }
        """

        when:
        def result = runner('listImages').build()

        then:
        result.task(':listImages').outcome == SUCCESS
        argsLog.text.trim() == '--log-level info images -a'
    }

    def "dryRun prints the command without invoking podman"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.podman' }

            podman { executable = '${fakeBin.absolutePath}' }

            tasks.register('buildImage', io.github.nhwalker.podman.gradle.tasks.PodmanBuildTask) {
                tags = ['example/app:latest']
                dryRun = true
            }
        """

        when:
        def result = runner('buildImage').build()

        then:
        result.task(':buildImage').outcome == SUCCESS
        result.output.contains('[dry-run]')
        result.output.contains('build -t example/app:latest')
        !argsLog.exists()
    }

    def "is compatible with the configuration cache"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.podman' }

            podman { executable = '${fakeBin.absolutePath}' }

            tasks.register('ping', io.github.nhwalker.podman.gradle.tasks.PodmanExecTask) {
                arguments = ['version']
            }
        """

        when:
        runner('ping', '--configuration-cache').build()
        def result = runner('ping', '--configuration-cache').build()

        then:
        result.output.contains('Reusing configuration cache.')
        result.task(':ping').outcome == SUCCESS
    }
}
