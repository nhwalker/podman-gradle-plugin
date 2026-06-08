package io.github.nhwalker.container.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.FAILED
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

    def "tag task runs podman tag once per target image"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${fakeBin.absolutePath}' }

            tasks.register('tagImage', io.github.nhwalker.container.gradle.tasks.ContainerTagTask) {
                sourceImage = 'example/app:latest'
                targetImages = ['registry.example.com/app:1.0', 'registry.example.com/app:latest']
            }
        """

        when:
        def result = runner('tagImage').build()

        then: 'one tag invocation per target, in declared order'
        result.task(':tagImage').outcome == SUCCESS
        argsLog.readLines() == [
                'tag example/app:latest registry.example.com/app:1.0',
                'tag example/app:latest registry.example.com/app:latest',
        ]
    }

    def "ignoreExitValue tolerates a non-zero exit"() {
        given:
        def failing = new File(testProjectDir, 'fake-podman-fail')
        failing << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
exit 3
"""
        failing.setExecutable(true)
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${failing.absolutePath}' }

            tasks.register('stopApp', io.github.nhwalker.container.gradle.tasks.ContainerStopTask) {
                containers = ['app']
                ignoreExitValue = true
            }
        """

        when:
        def result = runner('stopApp').build()

        then: 'the non-zero exit is logged, not fatal'
        result.task(':stopApp').outcome == SUCCESS
        argsLog.readLines() == ['stop app']
    }

    def "a non-zero exit fails the task when not ignored"() {
        given:
        def failing = new File(testProjectDir, 'fake-podman-fail')
        failing << """#!/usr/bin/env sh
exit 3
"""
        failing.setExecutable(true)
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${failing.absolutePath}' }

            tasks.register('stopApp', io.github.nhwalker.container.gradle.tasks.ContainerStopTask) {
                containers = ['app']
            }
        """

        when:
        def result = runner('stopApp').buildAndFail()

        then:
        result.task(':stopApp').outcome == FAILED
    }

    def "captureOutput exposes the process stdout via getStandardOutput"() {
        given:
        def echo = new File(testProjectDir, 'fake-podman-echo')
        echo << """#!/usr/bin/env sh
echo 'repo/app:1.0'
exit 0
"""
        echo.setExecutable(true)
        def captured = new File(testProjectDir, 'captured.txt')
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${echo.absolutePath}' }

            tasks.register('listImages', io.github.nhwalker.container.gradle.tasks.ContainerExecTask) {
                arguments = ['images']
                captureOutput = true
                doLast { new File('${captured.absolutePath}').text = standardOutput }
            }
        """

        when:
        def result = runner('listImages').build()

        then: 'stdout was captured to the buffer and handed to the doLast block'
        result.task(':listImages').outcome == SUCCESS
        captured.text.trim() == 'repo/app:1.0'
    }

    def "copies multiple paths out of an image and removes the temporary container"() {
        given:
        def cpFake = new File(testProjectDir, 'fake-podman-cp-multi')
        cpFake << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
if [ "\$1" = "create" ]; then echo "cid123"; fi
exit 0
"""
        cpFake.setExecutable(true)
        def d1 = new File(testProjectDir, 'out/a.jar').absolutePath
        def d2 = new File(testProjectDir, 'out/b.txt').absolutePath
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${cpFake.absolutePath}' }

            tasks.register('extract', io.github.nhwalker.container.gradle.tasks.ContainerCopyFromImageTask) {
                image = 'example/app:latest'
                paths = ['/app/a.jar': '${d1}', '/app/b.txt': '${d2}']
            }
        """

        when:
        def result = runner('extract').build()

        then: 'create once, a cp per path, then rm -f the container'
        result.task(':extract').outcome == SUCCESS
        def log = argsLog.readLines()
        log.first() == 'create example/app:latest'
        log.contains('cp cid123:/app/a.jar ' + d1)
        log.contains('cp cid123:/app/b.txt ' + d2)
        log.last() == 'rm -f cid123'
    }

    def "skips removing the temporary container when removeContainer is false"() {
        given:
        def cpFake = new File(testProjectDir, 'fake-podman-cp-keep')
        cpFake << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
if [ "\$1" = "create" ]; then echo "cid123"; fi
exit 0
"""
        cpFake.setExecutable(true)
        def dest = new File(testProjectDir, 'out/a.jar').absolutePath
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${cpFake.absolutePath}' }

            tasks.register('extract', io.github.nhwalker.container.gradle.tasks.ContainerCopyFromImageTask) {
                image = 'example/app:latest'
                removeContainer = false
                paths = ['/app/a.jar': '${dest}']
            }
        """

        when:
        def result = runner('extract').build()

        then: 'the container is created and copied from but never removed'
        result.task(':extract').outcome == SUCCESS
        def log = argsLog.readLines()
        log == ['create example/app:latest', 'cp cid123:/app/a.jar ' + dest]
        !log.any { it.startsWith('rm ') }
    }

    def "copies from an existing container without creating or removing one"() {
        given:
        def dest = new File(testProjectDir, 'out/log.txt').absolutePath
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${fakeBin.absolutePath}' }

            tasks.register('extract', io.github.nhwalker.container.gradle.tasks.ContainerCopyFromImageTask) {
                container = 'running'
                paths = ['/var/log/app.log': '${dest}']
            }
        """

        when:
        def result = runner('extract').build()

        then: 'only the cp runs — no create, no rm'
        result.task(':extract').outcome == SUCCESS
        argsLog.readLines() == ['cp running:/var/log/app.log ' + dest]
    }

    def "generates an SBOM by running syft in a container over the saved archive"() {
        given:
        // A fake podman that writes a dummy tar for `save -o`, emits a CycloneDX
        // document on stdout for `run` (the syft container), and logs every call.
        def sbomFake = new File(testProjectDir, 'fake-podman-sbom')
        sbomFake << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
case "\$1" in
  run)
    echo '{"bomFormat":"CycloneDX","specVersion":"1.5","components":[]}'
    ;;
  save)
    out=""
    while [ \$# -gt 0 ]; do
      if [ "\$1" = "-o" ]; then out="\$2"; fi
      shift
    done
    [ -n "\$out" ] && echo 'dummy-tar' > "\$out"
    ;;
esac
exit 0
"""
        sbomFake.setExecutable(true)
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container {
                executable = '${sbomFake.absolutePath}'
                syftImage = 'docker.io/anchore/syft:v1.18.1'
                images { foo { tags = ['example/foo:1.0']; generateSbom = true } }
            }
        """

        when:
        def result = runner('generateFooImageSbom').build()

        then: 'the image is built and saved, then scanned with the syft container'
        result.task(':generateFooImageSbom').outcome == SUCCESS
        def log = argsLog.readLines()
        log.any { it.startsWith('build ') }
        log.any { it.startsWith('save ') }
        log.any { it.contains('docker.io/anchore/syft:v1.18.1') && it.contains('scan oci-archive:/scan/image.tar') }

        and: 'the captured CycloneDX document is written to the sbom file'
        def sbom = new File(testProjectDir, 'build/container/foo/foo-sbom.cyclonedx.json')
        sbom.isFile()
        sbom.text.contains('"bomFormat":"CycloneDX"')
    }

    def "dryRun prints the sbom command without invoking podman run"() {
        given:
        // Only the save step actually runs (to produce the scanned tar input); the sbom
        // step is dry-run, so it logs the plan and never invokes the syft container.
        def saveFake = new File(testProjectDir, 'fake-podman-save')
        saveFake << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
if [ "\$1" = "save" ]; then
  out=""
  while [ \$# -gt 0 ]; do
    if [ "\$1" = "-o" ]; then out="\$2"; fi
    shift
  done
  [ -n "\$out" ] && echo 'dummy-tar' > "\$out"
fi
exit 0
"""
        saveFake.setExecutable(true)
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container {
                executable = '${saveFake.absolutePath}'
                images { foo { tags = ['example/foo:1.0']; generateSbom = true } }
            }
            tasks.withType(io.github.nhwalker.container.gradle.tasks.ContainerSbomTask).configureEach {
                dryRun = true
            }
        """

        when:
        def result = runner('generateFooImageSbom').build()

        then: 'the syft run is only printed, and no sbom file is written'
        result.task(':generateFooImageSbom').outcome == SUCCESS
        result.output.contains('[dry-run]')
        result.output.contains('run --rm --pull missing')
        result.output.contains('scan oci-archive:/scan/image.tar -o cyclonedx-json')
        !argsLog.readLines().any { it.startsWith('run ') }
        !new File(testProjectDir, 'build/container/foo/foo-sbom.cyclonedx.json').exists()
    }

    def "dryRun prints the create, copy and remove plan without executing"() {
        given:
        def dest = new File(testProjectDir, 'out/a.jar').absolutePath
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }

            container { executable = '${fakeBin.absolutePath}' }

            tasks.register('extract', io.github.nhwalker.container.gradle.tasks.ContainerCopyFromImageTask) {
                image = 'example/app:latest'
                paths = ['/app/a.jar': '${dest}']
                dryRun = true
            }
        """

        when:
        def result = runner('extract').build()

        then: 'the full plan is logged and podman is never invoked'
        result.task(':extract').outcome == SUCCESS
        result.output.contains('[dry-run]')
        result.output.contains('create example/app:latest')
        result.output.contains('cp <container-from example/app:latest>:/app/a.jar ' + dest)
        result.output.contains('rm -f <container-from example/app:latest>')
        !argsLog.exists()
    }
}
