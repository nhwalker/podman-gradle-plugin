package io.github.nhwalker.publish

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * Functional tests for the single aggregated, publishable software component shared by the
 * container, helm, and generic-artifacts plugins. Every applied plugin contributes its variants to
 * one {@code genericArtifacts} component (and, when {@code java} is applied, additionally to
 * {@code components.java}), so a project publishes one coherent module. Also covers the
 * {@code defaultArtifact} opt-in that publishes one artifact without a Maven classifier.
 *
 * <p>Fake {@code podman}/{@code helm} scripts let the full publish path run without the real binaries.
 */
class AggregatedComponentFunctionalSpec extends Specification {

    @TempDir
    File dir

    File fakePodman
    File fakeHelm

    def setup() {
        new File(dir, 'settings.gradle') << "rootProject.name = 'platform'\n"

        // Fake podman: `save` writes the -o output file; `image inspect` echoes a digest; else exit 0.
        fakePodman = new File(dir, 'fake-podman')
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
        fakeHelm = new File(dir, 'fake-helm')
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
                .withProjectDir(dir)
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput()
    }

    private void writeContainerfile() {
        def cdir = new File(dir, 'src/main/container'); cdir.mkdirs()
        new File(cdir, 'Containerfile') << "FROM scratch\n"
    }

    private void writeChart(String name) {
        def hdir = new File(dir, "src/main/helm/${name}"); hdir.mkdirs()
        new File(hdir, 'Chart.yaml') << "apiVersion: v2\nname: ${name}\nversion: 0.0.0\n"
    }

    /**
     * Writes a project applying container + helm + artifacts (+ optional java) declaring an image
     * 'app', a chart 'svc', and a produced 'data' artifact, publishing the given component.
     */
    private void writeProject(Map opts = [:]) {
        boolean java = opts.get('java', false)
        String component = opts.get('component', 'genericArtifacts')
        boolean createArchive = opts.get('createArchive', true)
        boolean chartDefault = opts.get('chartDefault', false)
        String imageDefault = opts.get('imageDefault', null)
        boolean archive = opts.get('archive', false)
        writeContainerfile()
        writeChart('svc')
        new File(dir, 'build.gradle') << """
            plugins {
                ${java ? "id 'java'" : ''}
                id 'io.github.nhwalker.container'
                id 'io.github.nhwalker.helm'
                id 'io.github.nhwalker.artifacts'
                id 'maven-publish'
            }
            group = 'com.example'
            version = '1.0'
            ${java ? 'java { withSourcesJar() }' : ''}

            container {
                executable = '${fakePodman.absolutePath}'
                images { app {
                    containerfile = layout.projectDirectory.file('src/main/container/Containerfile')
                    contextDirectory = layout.projectDirectory.dir('src/main/container')
                    tags = ['example/app:1.0']
                    ${createArchive ? 'createArchive = true' : ''}
                    ${imageDefault != null ? "defaultArtifact = '${imageDefault}'" : ''}
                } }
                ${archive ? 'archives { allImages { image images.app } }' : ''}
            }

            helm {
                executable = '${fakeHelm.absolutePath}'
                charts { svc {
                    ${chartDefault ? 'defaultArtifact = true' : ''}
                } }
            }

            def dataFile = layout.buildDirectory.file('data/data.txt')
            def makeData = tasks.register('makeData') {
                outputs.file(dataFile)
                doLast { dataFile.get().asFile.text = 'generic-payload' }
            }
            genericArtifacts { produce { data { artifact makeData.map { dataFile.get() } } } }

            publishing {
                publications { maven(MavenPublication) { from components.${component} } }
                repositories { maven { name = 'test'; url = layout.buildDirectory.dir('repo').get().asFile.toURI() } }
            }
        """
    }

    private String moduleJson() {
        new File(dir, 'build/publications/maven/module.json').text
    }

    private File repoDir() {
        new File(dir, 'build/repo/com/example/platform/1.0')
    }

    def "container + helm + artifacts publish one module carrying all variants (no java)"() {
        given:
        writeProject()

        when:
        def result = runner('publishMavenPublicationToTestRepository').build()

        then: 'each producing task ran'
        result.task(':publishMavenPublicationToTestRepository').outcome == SUCCESS
        result.task(':saveAppImage').outcome == SUCCESS
        result.task(':packageSvcChart').outcome == SUCCESS
        result.task(':makeData').outcome == SUCCESS

        and: 'the one module carries the image, chart, and generic variants'
        def module = moduleJson()
        module.contains('"io.github.nhwalker.container.imageName": "app"')
        module.contains('"io.github.nhwalker.container.imageType": "reference"')
        module.contains('"io.github.nhwalker.container.imageType": "archive"')
        module.contains('"io.github.nhwalker.helm.chartName": "svc"')
        module.contains('"io.github.nhwalker.artifacts.classifier": "data"')
        module.contains('"io.github.nhwalker.artifacts.ecosystem": "generic-artifact"')

        and: 'every artifact landed under the one GAV with its classifier'
        new File(repoDir(), 'platform-1.0-app-reference.txt').exists()
        new File(repoDir(), 'platform-1.0-app.tar').exists()
        new File(repoDir(), 'platform-1.0-svc.tgz').exists()
        new File(repoDir(), 'platform-1.0-data.txt').text == 'generic-payload'
    }

    def "with java applied, components.java folds in the image/chart/artifact variants alongside the jar"() {
        given:
        writeProject(java: true, component: 'java')

        when:
        def result = runner('publishMavenPublicationToTestRepository').build()

        then:
        result.task(':publishMavenPublicationToTestRepository').outcome == SUCCESS

        and: 'the module carries both JVM variants and our variants'
        def module = moduleJson()
        module.contains('"name": "apiElements"')
        module.contains('"name": "runtimeElements"')
        module.contains('"io.github.nhwalker.container.imageName": "app"')
        module.contains('"io.github.nhwalker.helm.chartName": "svc"')
        module.contains('"io.github.nhwalker.artifacts.classifier": "data"')

        and: 'the jar and the classified artifacts publish under one module'
        new File(repoDir(), 'platform-1.0.jar').exists()
        new File(repoDir(), 'platform-1.0-app.tar').exists()
        new File(repoDir(), 'platform-1.0-svc.tgz').exists()
    }

    def "from components.genericArtifacts skips the jar even when java is applied"() {
        given:
        writeProject(java: true, component: 'genericArtifacts')

        when:
        def result = runner('publishMavenPublicationToTestRepository').build()

        then:
        result.task(':publishMavenPublicationToTestRepository').outcome == SUCCESS

        and: 'no JVM variants and no jar are published'
        def module = moduleJson()
        !module.contains('"name": "apiElements"')
        !module.contains('"name": "runtimeElements"')
        repoDir().listFiles().findAll { it.name.endsWith('.jar') }.isEmpty()

        and: 'our image/chart/artifact variants are present'
        module.contains('"io.github.nhwalker.container.imageName": "app"')
        module.contains('"io.github.nhwalker.helm.chartName": "svc"')
        module.contains('"io.github.nhwalker.artifacts.classifier": "data"')
    }

    def "the per-plugin container/helm components are replaced by the aggregate genericArtifacts component"() {
        given:
        writeProject()
        new File(dir, 'build.gradle') << """
            tasks.register('printComponents') { doLast {
                println "HAS_GENERIC=" + (project.components.findByName('genericArtifacts') != null)
                println "HAS_CONTAINER=" + (project.components.findByName('container') != null)
                println "HAS_HELM=" + (project.components.findByName('helm') != null)
            } }
        """

        when:
        def result = runner('printComponents').build()

        then:
        result.output.contains('HAS_GENERIC=true')
        result.output.contains('HAS_CONTAINER=false')
        result.output.contains('HAS_HELM=false')
    }

    def "defaultArtifact publishes the chart without a classifier while keeping its selection attribute"() {
        given:
        writeProject(chartDefault: true)

        when:
        def result = runner('publishMavenPublicationToTestRepository').build()

        then:
        result.task(':publishMavenPublicationToTestRepository').outcome == SUCCESS

        and: 'the chart is the module default (no classifier); the classified name is gone'
        new File(repoDir(), 'platform-1.0.tgz').exists()
        !new File(repoDir(), 'platform-1.0-svc.tgz').exists()

        and: 'the variant still carries the chartName attribute, so Gradle selection is unchanged'
        moduleJson().contains('"io.github.nhwalker.helm.chartName": "svc"')

        and: 'the POM packaging is the default artifact extension'
        new File(dir, 'build/publications/maven/pom-default.xml').text.contains('<packaging>tgz</packaging>')
    }

    def "an image archive can be the default artifact"() {
        given:
        writeProject(imageDefault: 'archive')

        when:
        def result = runner('publishMavenPublicationToTestRepository').build()

        then:
        result.task(':publishMavenPublicationToTestRepository').outcome == SUCCESS
        new File(repoDir(), 'platform-1.0.tar').exists()
        !new File(repoDir(), 'platform-1.0-app.tar').exists()
        moduleJson().contains('"io.github.nhwalker.container.imageType": "archive"')
    }

    def "a multi-image archive variant folds into components.java alongside the jar and image variants"() {
        given:
        writeProject(java: true, component: 'java', archive: true)

        when:
        def result = runner('publishMavenPublicationToTestRepository').build()

        then: 'the multi-image archive is saved and published in the one module'
        result.task(':publishMavenPublicationToTestRepository').outcome == SUCCESS
        result.task(':saveAllImagesArchive').outcome == SUCCESS

        and: 'the module carries the jar plus the bundle variant (imageName=allImages, imageType=archive)'
        def module = moduleJson()
        module.contains('"name": "runtimeElements"')
        module.contains('"io.github.nhwalker.container.imageName": "allImages"')
        module.contains('"io.github.nhwalker.container.imageType": "archive"')
        new File(repoDir(), 'platform-1.0.jar').exists()
        new File(repoDir(), 'platform-1.0-allImages.tar').exists()
    }

    def "designating two default artifacts across plugins fails, naming both"() {
        given:
        writeProject(chartDefault: true, imageDefault: 'reference')

        when:
        def result = runner('help').buildAndFail()

        then:
        result.output.contains('Only one artifact may be the module')
        result.output.contains("helm chart 'svc'")
        result.output.contains("container image 'app' reference")
    }

    def "defaultArtifact 'archive' without createArchive fails"() {
        given:
        writeProject(createArchive: false, imageDefault: 'archive')

        when:
        def result = runner('help').buildAndFail()

        then:
        result.output.contains("requires createArchive = true")
    }

    def "an unknown container defaultArtifact selector fails"() {
        given:
        writeProject(imageDefault: 'bogus')

        when:
        def result = runner('help').buildAndFail()

        then:
        result.output.contains("defaultArtifact must be 'archive' or 'reference'")
    }

    def "with java applied, a default artifact warns and the jar stays the primary artifact"() {
        given:
        writeProject(java: true, component: 'java', chartDefault: true)

        when:
        def result = runner('publishMavenPublicationToTestRepository').build()

        then: 'the warning explains the jar stays primary for components.java'
        result.output.contains("jar remains the module's primary")

        and: 'the jar is the unclassified main artifact, and the chart also publishes unclassified'
        new File(repoDir(), 'platform-1.0.jar').exists()
        new File(repoDir(), 'platform-1.0.tgz').exists()
    }

    def "publishing the aggregated module is configuration-cache compatible"() {
        given:
        writeProject()
        runner('publishMavenPublicationToTestRepository', '--configuration-cache').build()

        when:
        def result = runner('publishMavenPublicationToTestRepository', '--configuration-cache').build()

        then:
        result.output.contains('Reusing configuration cache.')
        result.task(':publishMavenPublicationToTestRepository').outcome in [SUCCESS, UP_TO_DATE]
    }
}
