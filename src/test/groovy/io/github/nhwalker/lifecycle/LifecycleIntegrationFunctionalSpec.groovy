package io.github.nhwalker.lifecycle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * Functional tests for tying the three plugins into the standard lifecycle tasks
 * ({@code assemble}/{@code check}/{@code build}). Each plugin applies {@code lifecycle-base}
 * and, by default, wires its production tasks into {@code assemble} (helm lint into
 * {@code check}); the {@code lifecycleIntegration} flag opts out project-wide with a per-item
 * override. Fake {@code podman}/{@code helm} scripts let the full execution path run without
 * the real binaries.
 */
class LifecycleIntegrationFunctionalSpec extends Specification {

    @TempDir
    File testProjectDir

    File buildFile
    File fakePodman
    File fakeHelm

    def setup() {
        buildFile = new File(testProjectDir, 'build.gradle')
        new File(testProjectDir, 'settings.gradle') << "rootProject.name = 'fixture'\n"

        // Fake podman: `build` exits 0; `save` writes the -o output file; `image inspect`
        // echoes a digest so any reference task can run.
        fakePodman = new File(testProjectDir, 'fake-podman')
        fakePodman << """#!/usr/bin/env sh
if [ "\$1" = "save" ]; then
  prev=""
  for a in "\$@"; do
    if [ "\$prev" = "-o" ]; then mkdir -p "\$(dirname "\$a")"; : > "\$a"; fi
    prev="\$a"
  done
fi
if [ "\$1" = "image" ] && [ "\$2" = "inspect" ]; then echo "sha256:deadbeef"; fi
exit 0
"""
        fakePodman.setExecutable(true)

        // Fake helm: `package` drops a .tgz into --destination; everything else exits 0.
        fakeHelm = new File(testProjectDir, 'fake-helm')
        fakeHelm << """#!/usr/bin/env sh
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
        fakeHelm.setExecutable(true)
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput()
    }

    private void writeContainerfile() {
        def dir = new File(testProjectDir, 'src/main/container')
        dir.mkdirs()
        new File(dir, 'Containerfile') << "FROM scratch\n"
    }

    private void writeChart(String name) {
        def dir = new File(testProjectDir, "src/main/helm/${name}")
        dir.mkdirs()
        new File(dir, 'Chart.yaml') << "apiVersion: v2\nname: ${name}\nversion: 0.0.0\n"
    }

    // ---- container ---------------------------------------------------------------

    def "assemble builds the image and its archive by default"() {
        given:
        writeContainerfile()
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fakePodman.absolutePath}'
                images { app {
                    containerfile = layout.projectDirectory.file('src/main/container/Containerfile')
                    contextDirectory = layout.projectDirectory.dir('src/main/container')
                    tags = ['example/app:1.0']
                    createArchive = true
                } }
            }
        """

        when:
        def result = runner('assemble').build()

        then:
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':saveAppImage').outcome == SUCCESS
    }

    def "a project-wide opt-out keeps images out of assemble (but they stay runnable by name)"() {
        given:
        writeContainerfile()
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fakePodman.absolutePath}'
                lifecycleIntegration = false
                images { app {
                    containerfile = layout.projectDirectory.file('src/main/container/Containerfile')
                    contextDirectory = layout.projectDirectory.dir('src/main/container')
                    tags = ['example/app:1.0']
                } }
            }
        """

        when:
        def assembleResult = runner('assemble').build()

        then: 'assemble does not pull the image build in'
        assembleResult.task(':buildAppImage') == null

        when: 'the task is still runnable directly'
        def directResult = runner('buildAppImage').build()

        then:
        directResult.task(':buildAppImage').outcome == SUCCESS
    }

    def "a per-image opt-in overrides a project-wide opt-out"() {
        given:
        writeContainerfile()
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fakePodman.absolutePath}'
                lifecycleIntegration = false
                images { app {
                    containerfile = layout.projectDirectory.file('src/main/container/Containerfile')
                    contextDirectory = layout.projectDirectory.dir('src/main/container')
                    tags = ['example/app:1.0']
                    lifecycleIntegration = true
                } }
            }
        """

        when:
        def result = runner('assemble').build()

        then:
        result.task(':buildAppImage').outcome == SUCCESS
    }

    // ---- helm --------------------------------------------------------------------

    def "assemble packages charts and check lints them by default"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'io.github.nhwalker.helm' }
            helm {
                executable = '${fakeHelm.absolutePath}'
                charts { api { } }
            }
        """

        when:
        def assembleResult = runner('assemble').build()

        then:
        assembleResult.task(':packageApiChart').outcome == SUCCESS
        assembleResult.task(':lintApiChart') == null

        when:
        def checkResult = runner('check').build()

        then:
        checkResult.task(':lintApiChart').outcome == SUCCESS
    }

    def "a per-chart opt-out keeps the chart out of assemble and check"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'io.github.nhwalker.helm' }
            helm {
                executable = '${fakeHelm.absolutePath}'
                charts { api { lifecycleIntegration = false } }
            }
        """

        when:
        def result = runner('build').build()

        then:
        result.task(':packageApiChart') == null
        result.task(':lintApiChart') == null
    }

    // ---- artifacts ---------------------------------------------------------------

    def "assemble builds produced artifacts by default"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.artifacts' }
            def makeThing = tasks.register('makeThing') {
                def out = layout.buildDirectory.file('thing.txt')
                outputs.file(out)
                doLast { out.get().asFile.text = 'thing\\n' }
            }
            genericArtifacts { produce { thing {
                artifact makeThing.map { layout.buildDirectory.file('thing.txt').get() }
            } } }
        """

        when:
        def result = runner('assemble').build()

        then:
        result.task(':makeThing').outcome == SUCCESS
    }

    def "a per-artifact opt-out keeps the producing task out of assemble"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.artifacts' }
            def makeThing = tasks.register('makeThing') {
                def out = layout.buildDirectory.file('thing.txt')
                outputs.file(out)
                doLast { out.get().asFile.text = 'thing\\n' }
            }
            genericArtifacts { produce { thing {
                lifecycleIntegration = false
                artifact makeThing.map { layout.buildDirectory.file('thing.txt').get() }
            } } }
        """

        when:
        def result = runner('assemble').build()

        then:
        result.task(':makeThing') == null
    }

    // ---- lifecycle-base ----------------------------------------------------------

    def "applying a plugin contributes the lifecycle tasks even without the java plugin"() {
        given:
        buildFile << """
            plugins { id 'io.github.nhwalker.container' }
            container { executable = '${fakePodman.absolutePath}' }
        """

        when:
        def result = runner('assemble', 'check', 'clean').build()

        then:
        result.task(':assemble') != null
        result.task(':check') != null
        result.task(':clean') != null
    }
}
