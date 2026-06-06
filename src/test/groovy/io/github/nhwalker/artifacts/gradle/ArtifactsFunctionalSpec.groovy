package io.github.nhwalker.artifacts.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Functional tests for the generic-artifact dependency model: cross-project and
 * composite-build resolution by classifier (proving the producing task is wired as a
 * dependency of consumption), publishing one module with classifier-selected variants
 * (proving the producing task is wired into publishing), and configuration-cache reuse.
 */
class ArtifactsFunctionalSpec extends Specification {

    @TempDir
    File dir

    private GradleRunner runner(File projectDir, String... args) {
        GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput()
    }

    /** The plugin-under-test classpath as a Groovy `files(...)` literal for buildscript injection. */
    private String pluginClasspathFilesLiteral() {
        def url = getClass().classLoader.getResource('plugin-under-test-metadata.properties')
        def props = new Properties()
        url.withInputStream { props.load(it) }
        def entries = props.getProperty('implementation-classpath').split(File.pathSeparator)
        'files(' + entries.collect { "'${it.replace('\\', '\\\\')}'" }.join(', ') + ')'
    }

    /** A producer build snippet declaring a `report` artifact produced by `makeReport`. */
    private String producerBody(String content) {
        """
            def reportFile = layout.buildDirectory.file('report.txt')
            tasks.register('makeReport') {
                outputs.file(reportFile)
                doLast { reportFile.get().asFile.text = '${content}' }
            }
            genericArtifacts {
                produce {
                    report {
                        attribute 'flavor', 'html'
                        artifact reportFile, { builtBy 'makeReport' }
                    }
                }
            }
        """
    }

    /** A consumer task that copies the single resolved file to build/resolved.txt. */
    private String consumerUseReportTask() {
        """
            tasks.register('useReport') {
                def files = genericArtifacts.consume.theReport.files
                inputs.files files
                def out = layout.buildDirectory.file('resolved.txt')
                outputs.file(out)
                doLast { out.get().asFile.text = files.singleFile.text }
            }
        """
    }

    def "a consumer resolves a producer's artifact by classifier and the producing task runs first"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='multi'\ninclude ':producer', ':consumer'\n"

        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            ${producerBody('hello-report')}
        """
        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            genericArtifacts {
                consume {
                    theReport { from project(':producer'); classifier = 'report'; attribute 'flavor', 'html' }
                }
            }
            ${consumerUseReportTask()}
        """

        when:
        def result = runner(dir, ':consumer:useReport').build()

        then: 'the producer task ran before the consumer task, and the resolved content matches'
        result.task(':producer:makeReport').outcome == SUCCESS
        result.task(':consumer:useReport').outcome == SUCCESS
        def order = result.tasks*.path
        order.indexOf(':producer:makeReport') < order.indexOf(':consumer:useReport')
        new File(dir, 'consumer/build/resolved.txt').text == 'hello-report'
    }

    def "resolution is configuration-cache compatible"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='multi'\ninclude ':producer', ':consumer'\n"
        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            ${producerBody('cc-report')}
        """
        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            genericArtifacts { consume { theReport { from project(':producer'); classifier = 'report'; attribute 'flavor', 'html' } } }
            ${consumerUseReportTask()}
        """

        when:
        runner(dir, ':consumer:useReport', '--configuration-cache').build()
        def result = runner(dir, ':consumer:useReport', '--configuration-cache').build()

        then:
        result.output.contains('Reusing configuration cache.')
        new File(dir, 'consumer/build/resolved.txt').text == 'cc-report'
    }

    def "publishes one module with a classifier-selected variant; the publish task builds the artifact"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='platform'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts'; id 'maven-publish' }
            group = 'com.example'
            version = '1.0'
            ${producerBody('published-report')}
            publishing {
                publications { maven(MavenPublication) { from components.genericArtifacts } }
                repositories { maven { name = 'test'; url = layout.buildDirectory.dir('repo').get().asFile.toURI() } }
            }
        """

        when:
        def result = runner(dir, 'publishMavenPublicationToTestRepository').build()

        then: 'publishing pulled in the producing task'
        result.task(':makeReport').outcome == SUCCESS
        result.task(':publishMavenPublicationToTestRepository').outcome == SUCCESS

        and: 'the module metadata carries the classifier (and free) attributes'
        def module = new File(dir, 'build/publications/maven/module.json').text
        module.contains('"io.github.nhwalker.artifacts.classifier": "report"')
        module.contains('"io.github.nhwalker.artifacts.ecosystem": "generic-artifact"')
        module.contains('"flavor": "html"')

        and: 'the artifact was published into the repo under its classifier'
        new File(dir, 'build/repo/com/example/platform/1.0/platform-1.0-report.txt').text == 'published-report'
    }

    def "a composite build substitutes an external coordinate, resolving the artifact by classifier"() {
        given: 'a standalone producer build addressed by group:name'
        def cp = pluginClasspathFilesLiteral()
        def producer = new File(dir, 'producer')
        producer.mkdirs()
        new File(producer, 'settings.gradle') << "rootProject.name='producer'\n"
        new File(producer, 'build.gradle') << """
            buildscript { dependencies { classpath ${cp} } }
            apply plugin: 'io.github.nhwalker.artifacts'
            group = 'com.example'
            version = '1.0'
            ${producerBody('composite-report')}
        """

        and: 'a consumer build that includes the producer and depends on the external coordinate'
        def consumer = new File(dir, 'consumer')
        consumer.mkdirs()
        new File(consumer, 'settings.gradle') << "rootProject.name='consumer'\nincludeBuild '../producer'\n"
        new File(consumer, 'build.gradle') << """
            buildscript { dependencies { classpath ${cp} } }
            apply plugin: 'io.github.nhwalker.artifacts'
            group = 'com.example'
            version = '1.0'
            genericArtifacts {
                consume {
                    theReport { from 'com.example:producer:1.0'; classifier = 'report'; attribute 'flavor', 'html' }
                }
            }
            ${consumerUseReportTask()}
        """

        when: 'no dependencySubstitution block anywhere'
        def result = GradleRunner.create()
                .withProjectDir(consumer)
                .withArguments(':useReport')
                .forwardOutput()
                .build()

        then: 'substitution resolved the included build and the producer artifact was consumed'
        result.task(':useReport').outcome == SUCCESS
        new File(consumer, 'build/resolved.txt').text == 'composite-report'
    }
}
