package io.github.nhwalker.helm.gradle

import io.github.nhwalker.helm.gradle.tasks.HelmExecTask
import io.github.nhwalker.helm.gradle.tasks.HelmLintTask
import io.github.nhwalker.helm.gradle.tasks.HelmPackageTask
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * Unit tests that exercise command assembly without invoking a real helm binary,
 * using ProjectBuilder to instantiate the plugin and its tasks.
 */
class HelmPluginSpec extends Specification {

    def project = ProjectBuilder.builder().build()

    def setup() {
        project.pluginManager.apply(HelmPlugin)
    }

    def "registers the helm extension"() {
        expect:
        project.extensions.findByName('helm') instanceof HelmExtension
    }

    def "exec task inherits executable and global options from the extension"() {
        given:
        project.extensions.getByType(HelmExtension).with {
            executable.set('/opt/helm')
            globalOptions.set(['--namespace', 'platform'])
        }
        def task = project.tasks.register('helmVersion', HelmExecTask) {
            it.arguments.set(['version', '--short'])
        }.get()

        expect:
        task.group == HelmPlugin.TASK_GROUP
        task.assembleCommand() == [
                '/opt/helm', '--namespace', 'platform',
                'version', '--short'
        ]
    }

    def "lint task assembles chart directory, strict flag and values files"() {
        given:
        def values = project.layout.projectDirectory.file('ci/values.yaml').asFile
        def task = project.tasks.register('lintApi', HelmLintTask) {
            it.chartDirectory.set(project.layout.projectDirectory.dir('src/main/helm/api'))
            it.valuesFiles.from(values)
        }.get()

        when:
        def cmd = task.assembleCommand()

        then:
        cmd.first() == 'helm'
        cmd[1] == 'lint'
        cmd[2].endsWith('src/main/helm/api')
        cmd.contains('--strict')
        cmd.containsAll(['--values', values.absolutePath])
    }

    def "lint task omits --strict when disabled"() {
        given:
        def task = project.tasks.register('lintApi', HelmLintTask) {
            it.chartDirectory.set(project.layout.projectDirectory.dir('src/main/helm/api'))
            it.strict.set(false)
        }.get()

        expect:
        !task.assembleCommand().contains('--strict')
    }

    def "package task renders version, app-version and a destination"() {
        given:
        def task = project.tasks.register('packageApi', HelmPackageTask) {
            it.chartDirectory.set(project.layout.projectDirectory.dir('src/main/helm/api'))
            it.chartVersion.set('1.2.3')
            it.appVersion.set('4.5.6')
            it.packagedChart.set(project.layout.buildDirectory.file('helm/api/api.tgz'))
        }.get()

        when:
        def cmd = task.assembleCommand()

        then:
        cmd.first() == 'helm'
        cmd[1] == 'package'
        cmd[2].endsWith('src/main/helm/api')
        cmd.containsAll(['--version', '1.2.3'])
        cmd.containsAll(['--app-version', '4.5.6'])
        cmd.contains('--destination')
    }
}
