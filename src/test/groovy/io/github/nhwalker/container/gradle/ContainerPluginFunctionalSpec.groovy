package io.github.nhwalker.container.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Functional tests using Gradle TestKit. A fake {@code podman} script on the
 * PATH records the arguments it was invoked with, so the full task execution
 * path (injection, exec, exit handling) is exercised without a real podman.
 */
class ContainerPluginFunctionalSpec extends Specification {

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

    def "runs a ContainerExecTask against the configured executable"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container {
                executable = '${fakeBin.absolutePath}'
                globalOptions = ['--log-level', 'info']
            }

            tasks.register('listImages', io.github.nhwalker.container.gradle.tasks.ContainerExecTask) {
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
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${fakeBin.absolutePath}' }

            tasks.register('buildImage', io.github.nhwalker.container.gradle.tasks.ContainerBuildTask) {
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

    def "copies files out of an image via a temporary container"() {
        given:
        // A fake podman that prints a container id for `create` (so the task can
        // resolve the container to copy from) and logs every invocation.
        def cpFake = new File(testProjectDir, 'fake-podman-cp')
        cpFake << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
if [ "\$1" = "create" ]; then echo "deadbeefcontainerid"; fi
exit 0
"""
        cpFake.setExecutable(true)

        def dest = new File(testProjectDir, 'out/app.jar').absolutePath
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${cpFake.absolutePath}' }

            tasks.register('extract', io.github.nhwalker.container.gradle.tasks.ContainerCopyFromImageTask) {
                image = 'example/app:latest'
                copyOptions = ['--archive']
                paths = ['/app/app.jar': '${dest}']
            }
        """

        when:
        def result = runner('extract').build()

        then:
        result.task(':extract').outcome == SUCCESS
        def log = argsLog.readLines()
        log[0] == 'create example/app:latest'
        log[1] == "cp --archive deadbeefcontainerid:/app/app.jar ${dest}"
        log[2] == 'rm -f deadbeefcontainerid'
        // the destination's parent directory is created for podman to write into
        new File(testProjectDir, 'out').isDirectory()
    }

    def "is compatible with the configuration cache"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${fakeBin.absolutePath}' }

            tasks.register('ping', io.github.nhwalker.container.gradle.tasks.ContainerExecTask) {
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
