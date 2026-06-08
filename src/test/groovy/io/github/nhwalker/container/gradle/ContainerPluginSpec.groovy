package io.github.nhwalker.container.gradle

import io.github.nhwalker.container.gradle.tasks.ContainerBuildTask
import io.github.nhwalker.container.gradle.tasks.ContainerCopyFromImageTask
import io.github.nhwalker.container.gradle.tasks.ContainerExecTask
import io.github.nhwalker.container.gradle.tasks.ContainerLoadTask
import io.github.nhwalker.container.gradle.tasks.ContainerPullTask
import io.github.nhwalker.container.gradle.tasks.ContainerPushTask
import io.github.nhwalker.container.gradle.tasks.ContainerRemoveContainerTask
import io.github.nhwalker.container.gradle.tasks.ContainerRemoveImageTask
import io.github.nhwalker.container.gradle.tasks.ContainerRunTask
import io.github.nhwalker.container.gradle.tasks.ContainerSaveTask
import io.github.nhwalker.container.gradle.tasks.ContainerStopTask
import org.gradle.api.InvalidUserDataException
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * Unit tests that exercise command assembly without invoking a real podman
 * binary, using ProjectBuilder to instantiate the plugin and its tasks.
 */
class ContainerPluginSpec extends Specification {

    def project = ProjectBuilder.builder().build()

    def setup() {
        project.pluginManager.apply(ContainerPlugin)
    }

    def "registers the container extension"() {
        expect:
        project.extensions.findByName('container') instanceof ContainerExtension
    }

    def "exec task inherits executable and global options from the extension"() {
        given:
        project.extensions.getByType(ContainerExtension).with {
            executable.set('/opt/podman')
            globalOptions.set(['--log-level', 'debug'])
            connection.set('remote-host')
        }
        def task = project.tasks.register('images', ContainerExecTask) {
            it.arguments.set(['images', '-a'])
        }.get()

        expect:
        task.group == ContainerPlugin.TASK_GROUP
        task.assembleCommand() == [
                '/opt/podman', '--log-level', 'debug',
                '--connection', 'remote-host',
                'images', '-a'
        ]
    }

    def "build task assembles tags, build args, flags and context"() {
        given:
        def task = project.tasks.register('buildImage', ContainerBuildTask) {
            it.tags.set(['example/app:latest', 'example/app:1.0'])
            it.buildArgs.set(['VERSION': '1.0'])
            it.labels.set(['org.opencontainers.image.source': 'git'])
            it.platform.set('linux/amd64')
            it.noCache.set(true)
            it.pull.set(true)
            it.contextDirectory.set(project.layout.projectDirectory.dir('ctx'))
        }.get()

        when:
        def cmd = task.assembleCommand()

        then:
        cmd.first() == 'podman'
        cmd.contains('build')
        cmd.containsAll(['-t', 'example/app:latest'])
        cmd.containsAll(['-t', 'example/app:1.0'])
        cmd.containsAll(['--build-arg', 'VERSION=1.0'])
        cmd.containsAll(['--label', 'org.opencontainers.image.source=git'])
        cmd.containsAll(['--platform', 'linux/amd64'])
        cmd.contains('--no-cache')
        cmd.contains('--pull')
        cmd.last().endsWith('ctx')
    }

    def "run task orders flags, image and container command"() {
        given:
        def task = project.tasks.register('runApp', ContainerRunTask) {
            it.image.set('example/app:latest')
            it.containerName.set('app')
            it.detach.set(true)
            it.remove.set(true)
            it.ports.set(['8080:80'])
            it.environment.set(['PROFILE': 'dev'])
            it.command.set(['--flag'])
        }.get()

        when:
        def cmd = task.assembleCommand()

        then:
        cmd == [
                'podman', 'run', '-d', '--rm',
                '--name', 'app',
                '-p', '8080:80',
                '-e', 'PROFILE=dev',
                'example/app:latest', '--flag'
        ]
    }

    def "copy-from-image renders a cp command for an existing container"() {
        given:
        def task = project.tasks.register('copyOut', ContainerCopyFromImageTask) {
            it.container.set('mycontainer')
            it.copyOptions.set(['--archive'])
            it.paths.set(['/app/app.jar': '/tmp/app.jar'])
        }.get()

        expect:
        task.assembleCommand() == [
                'podman', 'cp', '--archive',
                'mycontainer:/app/app.jar', '/tmp/app.jar'
        ]
    }

    def "copy-from-image requires exactly one of image or container"() {
        given:
        def task = project.tasks.register('copyOut', ContainerCopyFromImageTask) {
            it.paths.set(['/a': '/tmp/a'])
        }.get()

        when:
        task.execute()

        then:
        thrown(InvalidUserDataException)
    }

    def "push task renders tls-verify and destination"() {
        given:
        def task = project.tasks.register('pushImage', ContainerPushTask) {
            it.image.set('example/app:latest')
            it.destination.set('registry.example.com/example/app:latest')
            it.tlsVerify.set(false)
        }.get()

        expect:
        task.assembleCommand() == [
                'podman', 'push', '--tls-verify=false',
                'example/app:latest',
                'registry.example.com/example/app:latest'
        ]
    }

    def "pull task renders platform, tls-verify and image"() {
        given:
        def task = project.tasks.register('pull', ContainerPullTask) {
            it.image.set('example/app:1.0')
            it.platform.set('linux/arm64')
            it.tlsVerify.set(true)
        }.get()

        expect:
        task.assembleCommand() == [
                'podman', 'pull', '--platform', 'linux/arm64', '--tls-verify=true', 'example/app:1.0'
        ]
    }

    def "stop task renders the timeout and containers"() {
        given:
        def task = project.tasks.register('stop', ContainerStopTask) {
            it.containers.set(['a', 'b'])
            it.stopTimeout.set(5)
        }.get()

        expect:
        task.assembleCommand() == ['podman', 'stop', '--time', '5', 'a', 'b']
    }

    def "stop task renders the all flag"() {
        given:
        def task = project.tasks.register('stopAll', ContainerStopTask) {
            it.all.set(true)
            it.containers.set([])
        }.get()

        expect:
        task.assembleCommand() == ['podman', 'stop', '--all']
    }

    def "remove-container task renders force, volumes and containers"() {
        given:
        def task = project.tasks.register('rm', ContainerRemoveContainerTask) {
            it.containers.set(['app'])
            it.force.set(true)
            it.volumes.set(true)
        }.get()

        expect:
        task.assembleCommand() == ['podman', 'rm', '--force', '--volumes', 'app']
    }

    def "remove-image task renders force and images"() {
        given:
        def task = project.tasks.register('rmi', ContainerRemoveImageTask) {
            it.images.set(['example/app:1.0'])
            it.force.set(true)
        }.get()

        expect:
        task.assembleCommand() == ['podman', 'rmi', '--force', 'example/app:1.0']
    }

    def "save task renders format, output and image"() {
        given:
        def out = project.layout.buildDirectory.file('img.tar').get().asFile
        def task = project.tasks.register('save', ContainerSaveTask) {
            it.image.set('example/app:1.0')
            it.format.set('oci-archive')
            it.outputFile.set(project.layout.buildDirectory.file('img.tar'))
        }.get()

        expect:
        task.assembleCommand() == [
                'podman', 'save', '--format', 'oci-archive', '-o', out.absolutePath, 'example/app:1.0'
        ]
    }

    def "archive task saves the reference-file members (digest stripped) then the literal members"() {
        given: 'two reference files (one digest-pinned) and a literal image'
        def baseRef = new File(project.projectDir, 'base-ref.txt')
        baseRef.text = 'example/base:1.0@sha256:deadbeef\n'
        def appRef = new File(project.projectDir, 'app-ref.txt')
        appRef.text = 'example/app:1.0\n'
        def out = project.layout.buildDirectory.file('bundle.tar').get().asFile
        def task = project.tasks.register('saveBundleArchive', io.github.nhwalker.container.gradle.tasks.ContainerArchiveTask) {
            it.imageReferenceFiles.from(baseRef, appRef)
            it.imageStrings.set(['docker.io/library/alpine:3.20'])
            it.format.set('oci-archive')
            it.pullPolicy.set('missing')
            it.outputFile.set(project.layout.buildDirectory.file('bundle.tar'))
        }.get()

        expect: 'name:tag from each ref file (digest dropped), reference members first, then literals'
        task.assembleCommand() == [
                'podman', 'save', '--format', 'oci-archive', '-o', out.absolutePath,
                'example/base:1.0', 'example/app:1.0', 'docker.io/library/alpine:3.20'
        ]
    }

    def "archive task fails when no members are declared"() {
        given:
        def task = project.tasks.register('saveEmptyArchive', io.github.nhwalker.container.gradle.tasks.ContainerArchiveTask) {
            it.outputFile.set(project.layout.buildDirectory.file('empty.tar'))
        }.get()

        when:
        task.assembleCommand()

        then:
        thrown(InvalidUserDataException)
    }

    def "load task renders the input archive"() {
        given:
        def input = project.layout.buildDirectory.file('img.tar').get().asFile
        def task = project.tasks.register('load', ContainerLoadTask) {
            it.inputFile.set(project.layout.buildDirectory.file('img.tar'))
        }.get()

        expect:
        task.assembleCommand() == ['podman', 'load', '-i', input.absolutePath]
    }
}
