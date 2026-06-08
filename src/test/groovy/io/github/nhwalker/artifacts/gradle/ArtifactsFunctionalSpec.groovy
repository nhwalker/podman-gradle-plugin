package io.github.nhwalker.artifacts.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import java.util.zip.ZipFile

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

    def "consumes a generic artifact by classifier from a project that ALSO publishes JVM variants"() {
        given: 'a producer applying java + our plugin, so the module has both JVM and generic variants'
        new File(dir, 'settings.gradle') << "rootProject.name='mixed'\ninclude ':producer', ':consumer'\n"
        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts'; id 'java'; id 'maven-publish' }
            group = 'com.example'; version = '1.0'
            java { withSourcesJar() }
            ${producerBody('mixed-report')}
        """
        new File(dir, 'producer/src/main/java/com/example').mkdirs()
        new File(dir, 'producer/src/main/java/com/example/A.java') << 'package com.example; public class A {}\n'

        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            genericArtifacts { consume { theReport { from project(':producer'); classifier = 'report' } } }
            ${consumerUseReportTask()}
        """

        when:
        def result = runner(dir, ':consumer:useReport').build()

        then: 'classifier alone selected our generic variant, not the jar/sources/javadoc'
        result.task(':consumer:useReport').outcome == SUCCESS
        new File(dir, 'consumer/build/resolved.txt').text == 'mixed-report'
    }

    def "consumes a native JVM sources jar from another project via native attributes (same API)"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='native'\ninclude ':lib', ':consumer'\n"
        new File(dir, 'lib').mkdirs()
        new File(dir, 'lib/build.gradle') << """
            plugins { id 'java' }
            group = 'com.example'; version = '1.0'
            java { withSourcesJar() }
        """
        new File(dir, 'lib/src/main/java/com/example').mkdirs()
        new File(dir, 'lib/src/main/java/com/example/A.java') << 'package com.example; public class A {}\n'

        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            genericArtifacts { consume { libSources { from project(':lib'); sources() } } }
            tasks.register('grab', Copy) { from genericArtifacts.consume.libSources.files; into layout.buildDirectory.dir('out') }
        """

        when:
        def result = runner(dir, ':consumer:grab').build()

        then:
        result.task(':consumer:grab').outcome == SUCCESS
        new File(dir, 'consumer/build/out').listFiles().any { it.name.endsWith('-sources.jar') }
    }

    def "consumes a plain Maven-repo artifact by classifier notation (same API)"() {
        given: 'publish a generic artifact to a local maven repo'
        def repo = new File(dir, 'repo')
        def producer = new File(dir, 'producer'); producer.mkdirs()
        new File(producer, 'settings.gradle') << "rootProject.name='platform'\n"
        new File(producer, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts'; id 'maven-publish' }
            group = 'com.example'; version = '1.0'
            ${producerBody('repo-report')}
            publishing {
                publications { maven(MavenPublication) { from components.genericArtifacts } }
                repositories { maven { name = 'test'; url = '${repo.toURI()}' } }
            }
        """
        runner(producer, 'publish').build()

        and: 'a separate consumer that resolves it from the repo by classifier notation'
        def consumer = new File(dir, 'consumer'); consumer.mkdirs()
        new File(consumer, 'settings.gradle') << "rootProject.name='consumer'\n"
        new File(consumer, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            repositories { maven { url = '${repo.toURI()}' } }
            genericArtifacts { consume { theReport { from 'com.example:platform:1.0:report@txt' } } }
            ${consumerUseReportTask()}
        """

        when:
        def result = runner(consumer, ':useReport').build()

        then:
        result.task(':useReport').outcome == SUCCESS
        new File(consumer, 'build/resolved.txt').text == 'repo-report'
    }

    def "downloadTask stages the resolved artifact into build/inputs/<name>"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='dl'\ninclude ':producer', ':consumer'\n"
        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            ${producerBody('staged-report')}
        """
        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            genericArtifacts {
                consume { theReport { from project(':producer'); classifier = 'report'; downloadTask() } }
            }
        """

        when:
        def result = runner(dir, ':consumer:downloadTheReport').build()

        then: 'the producing task ran (wired) and the file landed in the default directory'
        result.task(':producer:makeReport').outcome == SUCCESS
        result.task(':consumer:downloadTheReport').outcome == SUCCESS
        new File(dir, 'consumer/build/inputs/theReport/report.txt').text == 'staged-report'
    }

    def "unpackTask extracts a resolved zip archive, and is configuration-cache compatible"() {
        given: 'a producer that publishes a zip containing hello.txt as a generic artifact'
        new File(dir, 'settings.gradle') << "rootProject.name='unpack'\ninclude ':producer', ':consumer'\n"
        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            group = 'com.example'; version = '1.0'
            def payload = layout.buildDirectory.file('payload/hello.txt')
            tasks.register('makePayload') { outputs.file(payload); doLast { payload.get().asFile.text = 'inside-zip' } }
            tasks.register('makeZip', Zip) {
                dependsOn 'makePayload'
                archiveFileName = 'bundle.zip'
                destinationDirectory = layout.buildDirectory.dir('dist')
                from payload
            }
            genericArtifacts { produce { bundle { classifier = 'bundle'; artifact tasks.makeZip.archiveFile } } }
        """
        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            genericArtifacts {
                consume { theBundle { from project(':producer'); classifier = 'bundle'; unpackTask() } }
            }
        """

        when:
        def first = runner(dir, ':consumer:unpackTheBundle', '--configuration-cache').build()

        then: 'the producing zip task ran and the archive contents were extracted'
        first.task(':producer:makeZip').outcome == SUCCESS
        first.task(':consumer:unpackTheBundle').outcome == SUCCESS
        new File(dir, 'consumer/build/inputs/theBundle/hello.txt').text == 'inside-zip'

        when:
        def second = runner(dir, ':consumer:unpackTheBundle', '--configuration-cache').build()

        then:
        second.output.contains('Reusing configuration cache.')
        new File(dir, 'consumer/build/inputs/theBundle/hello.txt').text == 'inside-zip'
    }

    def "an arbitrary task depends on a staging task via the idempotent accessor + its output"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='wire'\ninclude ':producer', ':consumer'\n"
        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            ${producerBody('wired-report')}
        """
        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            genericArtifacts {
                consume { theReport { from project(':producer'); classifier = 'report'; downloadTask() } }
            }
            // a second, idempotent call returns the SAME task; consuming its output wires the dependency
            tasks.register('useStaged', Copy) {
                from genericArtifacts.consume.theReport.downloadTask()
                into layout.buildDirectory.dir('used')
            }
        """

        when:
        def result = runner(dir, ':consumer:useStaged').build()

        then: 'the staging task ran before the arbitrary task, and its staged content flowed through'
        result.task(':producer:makeReport').outcome == SUCCESS
        result.task(':consumer:downloadTheReport').outcome == SUCCESS
        result.task(':consumer:useStaged').outcome == SUCCESS
        def order = result.tasks*.path
        order.indexOf(':consumer:downloadTheReport') < order.indexOf(':consumer:useStaged')
        new File(dir, 'consumer/build/used/report.txt').text == 'wired-report'
    }

    def "exposes and consumes an application distribution archive as a generic artifact"() {
        given: 'an application project that publishes its distZip as a generic artifact'
        new File(dir, 'settings.gradle') << "rootProject.name='distmix'\ninclude ':producer', ':consumer'\n"
        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'application'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'; version = '1.0'
            application { mainClass = 'com.example.Main' }
            genericArtifacts { produce { dist { classifier = 'dist'; artifact tasks.distZip.archiveFile } } }
        """
        new File(dir, 'producer/src/main/java/com/example').mkdirs()
        new File(dir, 'producer/src/main/java/com/example/Main.java') <<
                'package com.example; public class Main { public static void main(String[] a) {} }\n'

        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            genericArtifacts { consume { theDist { from project(':producer'); classifier = 'dist' } } }
            tasks.register('grab', Copy) { from genericArtifacts.consume.theDist.files; into layout.buildDirectory.dir('out') }
        """

        when:
        def result = runner(dir, ':consumer:grab').build()

        then: 'distZip ran (wired as a build dependency) and classifier=dist selected the zip amid the JVM variants'
        result.task(':producer:distZip').outcome == SUCCESS
        result.task(':consumer:grab').outcome == SUCCESS
        new File(dir, 'consumer/build/out').listFiles().any { it.name.endsWith('.zip') }
    }

    def "importResourcesTask bundles the resolved artifact into the jar's main resources"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='res'\ninclude ':producer', ':consumer'\n"
        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            ${producerBody('imported-report')}
        """
        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            genericArtifacts {
                consume { theReport { from project(':producer'); classifier = 'report'; importResourcesTask() } }
            }
        """

        when:
        def result = runner(dir, ':consumer:jar').build()

        then: 'the producing task ran (wired) and the file was staged into the main resources'
        result.task(':producer:makeReport').outcome == SUCCESS
        result.task(':consumer:importTheReportResources').outcome == SUCCESS
        new File(dir, 'consumer/build/generated/resources/genericArtifacts/theReport/main/report.txt').text == 'imported-report'

        and: 'processResources picked the folder up so it lands in the jar resources'
        new File(dir, 'consumer/build/resources/main/report.txt').text == 'imported-report'
    }

    def "importUnpackedResourcesTask extracts the archive into resources, configuration-cache compatible"() {
        given: 'a producer that publishes a zip containing hello.txt as a generic artifact'
        new File(dir, 'settings.gradle') << "rootProject.name='resunpack'\ninclude ':producer', ':consumer'\n"
        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            group = 'com.example'; version = '1.0'
            def payload = layout.buildDirectory.file('payload/hello.txt')
            tasks.register('makePayload') { outputs.file(payload); doLast { payload.get().asFile.text = 'inside-zip' } }
            tasks.register('makeZip', Zip) {
                dependsOn 'makePayload'
                archiveFileName = 'bundle.zip'
                destinationDirectory = layout.buildDirectory.dir('dist')
                from payload
            }
            genericArtifacts { produce { bundle { classifier = 'bundle'; artifact tasks.makeZip.archiveFile } } }
        """
        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            genericArtifacts {
                consume { theBundle { from project(':producer'); classifier = 'bundle'; importUnpackedResourcesTask() } }
            }
        """

        when:
        def first = runner(dir, ':consumer:jar', '--configuration-cache').build()

        then: 'the producing zip task ran and the archive contents landed in the main resources'
        first.task(':producer:makeZip').outcome == SUCCESS
        first.task(':consumer:importTheBundleUnpackedResources').outcome == SUCCESS
        new File(dir, 'consumer/build/resources/main/hello.txt').text == 'inside-zip'

        when:
        def second = runner(dir, ':consumer:jar', '--configuration-cache').build()

        then:
        second.output.contains('Reusing configuration cache.')
        new File(dir, 'consumer/build/resources/main/hello.txt').text == 'inside-zip'
    }

    def "importResourcesTask targets a chosen source set and a subdirectory"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='restest'\ninclude ':producer', ':consumer'\n"
        new File(dir, 'producer').mkdirs()
        new File(dir, 'producer/build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            ${producerBody('test-report')}
        """
        new File(dir, 'consumer').mkdirs()
        new File(dir, 'consumer/build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            genericArtifacts {
                consume { theReport { from project(':producer'); classifier = 'report'; importResourcesTask('test') { into 'reports' } } }
            }
        """

        when:
        def result = runner(dir, ':consumer:processTestResources').build()

        then: 'the file was staged under the test source set resources at the requested subdirectory'
        result.task(':producer:makeReport').outcome == SUCCESS
        result.task(':consumer:importTheReportTestResources').outcome == SUCCESS
        new File(dir, 'consumer/build/generated/resources/genericArtifacts/theReport/test/reports/report.txt').text == 'test-report'
        new File(dir, 'consumer/build/resources/test/reports/report.txt').text == 'test-report'
    }

    def "produce importResourcesTask bundles the produced artifact into the jar's main resources"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='prod'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            def reportFile = layout.buildDirectory.file('report.txt')
            def makeReport = tasks.register('makeReport') { outputs.file(reportFile); doLast { reportFile.get().asFile.text = 'produced' } }
            genericArtifacts {
                produce { report { artifact makeReport.map { reportFile.get() }; importResourcesTask() } }
            }
        """

        when:
        def result = runner(dir, 'jar').build()

        then: 'the producing task ran (wired) and the produced file is bundled in the jar'
        result.task(':makeReport').outcome == SUCCESS
        result.task(':importReportResources').outcome == SUCCESS
        result.task(':jar').outcome == SUCCESS
        new File(dir, 'build/resources/main/report.txt').text == 'produced'
        new ZipFile(new File(dir, 'build/libs/prod.jar')).withCloseable { it.getEntry('report.txt') != null }
    }

    def "bundling a produced artifact exposes its resource path on the interface"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            def reportFile = layout.buildDirectory.file('report.txt')
            def makeReport = tasks.register('makeReport') { outputs.file(reportFile); doLast { reportFile.get().asFile.text = 'produced' } }
            genericArtifacts {
                referencesPackage = 'com.example'
                produce { report { artifact makeReport.map { reportFile.get() }; importResourcesTask { into 'reports' } } }
            }
        """

        when:
        def result = runner(dir, 'generateArtifactReferences').build()

        then: 'the bundle ran first and the interface exposes the bundled resource path'
        result.task(':importReportResources').outcome == SUCCESS
        result.task(':generateArtifactReferences').outcome == SUCCESS
        def generated = new File(dir,
                'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java')
        generated.exists()
        def text = generated.text
        text.contains('package com.example;')
        text.contains('public interface FixtureReferences')
        text.contains('public static final String REPORT = FixtureReferencesLoader.load("REPORT", "reports/report.txt");')
    }

    def "the produced-bundle references interface is configuration-cache compatible on a clean build"() {
        given: 'a produced artifact bundled into resources, generating a resource-path constant'
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            def reportFile = layout.buildDirectory.file('report.txt')
            def makeReport = tasks.register('makeReport') { outputs.file(reportFile); doLast { reportFile.get().asFile.text = 'produced' } }
            genericArtifacts {
                produce { report { artifact makeReport.map { reportFile.get() }; importResourcesTask() } }
                references { schemaVersion { value 'v3' } }
            }
        """

        when: 'a CLEAN build with the configuration cache (the bundle has not run at store time)'
        def first = runner(dir, 'generateArtifactReferences', '--configuration-cache').build()

        then: 'the bundle ran ahead of generation and its resource path resolved (not skipped/collapsed)'
        first.task(':importReportResources').outcome == SUCCESS
        first.task(':generateArtifactReferences').outcome == SUCCESS
        def generated = new File(dir,
                'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java').text
        generated.contains('public static final String REPORT = FixtureReferencesLoader.load("REPORT", "report.txt");')
        generated.contains('public static final String SCHEMA_VERSION = FixtureReferencesLoader.load("SCHEMA_VERSION", "v3");')

        when: 'a second run reuses the configuration cache'
        def second = runner(dir, 'generateArtifactReferences', '--configuration-cache').build()

        then:
        second.output.contains('Reusing configuration cache.')
    }

    def "an empty reference value contributes no constant without collapsing the interface"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            genericArtifacts {
                references {
                    present { value 'kept' }
                    blank   { value '' }
                }
            }
        """

        when:
        def result = runner(dir, 'generateArtifactReferences').build()

        then: 'the empty value is skipped while the present one is still emitted (no map collapse)'
        result.task(':generateArtifactReferences').outcome == SUCCESS
        def text = new File(dir,
                'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java').text
        text.contains('public static final String PRESENT = FixtureReferencesLoader.load("PRESENT", "kept");')
        !text.contains('BLANK')
    }

    def "references expose arbitrary string constants on the generated interface"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            genericArtifacts {
                references {
                    apiBaseUrl    { value = 'https://api.example.com' }
                    schemaVersion { value 'v3' }
                }
            }
        """

        when:
        def result = runner(dir, 'generateArtifactReferences').build()

        then: 'each declared reference is a constant carrying its arbitrary value'
        result.task(':generateArtifactReferences').outcome == SUCCESS
        def generated = new File(dir,
                'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java')
        generated.exists()
        def text = generated.text
        text.contains('public interface FixtureReferences')
        text.contains('public static final String API_BASE_URL = FixtureReferencesLoader.load("API_BASE_URL", "https://api.example.com");')
        text.contains('public static final String SCHEMA_VERSION = FixtureReferencesLoader.load("SCHEMA_VERSION", "v3");')
    }

    def "the generated interface merges bundled resource paths and arbitrary references"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            def reportFile = layout.buildDirectory.file('report.txt')
            def makeReport = tasks.register('makeReport') { outputs.file(reportFile); doLast { reportFile.get().asFile.text = 'produced' } }
            genericArtifacts {
                produce { report { artifact makeReport.map { reportFile.get() }; importResourcesTask() } }
                references { schemaVersion { value 'v3' } }
            }
        """

        when:
        def result = runner(dir, 'generateArtifactReferences').build()

        then: 'the bundle was wired and both kinds of constant appear in one interface'
        result.task(':importReportResources').outcome == SUCCESS
        result.task(':generateArtifactReferences').outcome == SUCCESS
        def text = new File(dir,
                'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java').text
        text.contains('public static final String REPORT = FixtureReferencesLoader.load("REPORT", "report.txt");')
        text.contains('public static final String SCHEMA_VERSION = FixtureReferencesLoader.load("SCHEMA_VERSION", "v3");')
    }

    def "references can target a non-main source set, generating a suffixed interface"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            genericArtifacts {
                references          { apiBaseUrl { value = 'https://api.example.com' } }
                references('test')  { stubUrl    { value = 'http://localhost:8080' } }
            }
        """

        when:
        def result = runner(dir, 'generateArtifactReferences', 'generateTestArtifactReferences').build()

        then: 'main and test each get their own interface in their own source set'
        result.task(':generateArtifactReferences').outcome == SUCCESS
        result.task(':generateTestArtifactReferences').outcome == SUCCESS

        and: 'main is unsuffixed and carries only the main reference'
        def main = new File(dir,
                'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java').text
        main.contains('public interface FixtureReferences ')
        main.contains('public static final String API_BASE_URL = FixtureReferencesLoader.load("API_BASE_URL", "https://api.example.com");')
        !main.contains('STUB_URL')

        and: 'test is suffixed and carries only the test reference'
        def test = new File(dir,
                'build/generated/sources/genericArtifactRefs/java/test/com/example/FixtureReferencesTest.java').text
        test.contains('public interface FixtureReferencesTest ')
        test.contains('public static final String STUB_URL = FixtureReferencesTestLoader.load("STUB_URL", "http://localhost:8080");')
        !test.contains('API_BASE_URL')
    }

    def "references are not generated without the java plugin"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            genericArtifacts {
                references { schemaVersion { value 'v3' } }
            }
        """

        when:
        def result = runner(dir, 'tasks').build()

        then: 'with no java source set to compile into, no generation task or interface exists'
        result.task(':generateArtifactReferences') == null
        !new File(dir, 'build/generated/sources/genericArtifactRefs').exists()
    }

    def "the generated interface name is customizable"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            genericArtifacts {
                referencesClassName = 'MyRefs'
                references          { apiBaseUrl { value = 'https://api.example.com' } }
                references('test')  { stubUrl    { value = 'http://localhost:8080' } }
            }
        """

        when:
        def result = runner(dir, 'generateArtifactReferences', 'generateTestArtifactReferences').build()

        then: 'the override is used as the main name and the source-set suffix is appended for test'
        result.task(':generateArtifactReferences').outcome == SUCCESS
        new File(dir, 'build/generated/sources/genericArtifactRefs/java/main/com/example/MyRefs.java')
                .text.contains('public interface MyRefs ')
        new File(dir, 'build/generated/sources/genericArtifactRefs/java/test/com/example/MyRefsTest.java')
                .text.contains('public interface MyRefsTest ')
    }

    def "fromFile captures a single-line file's contents as a normal string constant"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            def ref = layout.buildDirectory.file('ref.txt')
            def writeRef = tasks.register('writeRef') { outputs.file(ref); doLast { ref.get().asFile.text = 'example/app:1.0\\n' } }
            genericArtifacts {
                references { appImage { fromFile writeRef.map { ref.get() } } }
            }
        """

        when:
        def result = runner(dir, 'generateArtifactReferences').build()

        then: 'the producing task is wired ahead of generation and the trimmed contents become the value'
        result.task(':writeRef').outcome == SUCCESS
        result.task(':generateArtifactReferences').outcome == SUCCESS
        new File(dir, 'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java')
                .text.contains('public static final String APP_IMAGE = FixtureReferencesLoader.load("APP_IMAGE", "example/app:1.0");')
    }

    def "fromFile renders a multi-line document as a Java text block with the exact value"() {
        given:
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'application'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            def doc = layout.buildDirectory.file('doc.txt')
            def writeDoc = tasks.register('writeDoc') { outputs.file(doc); doLast { doc.get().asFile.text = '  line1\\nline2\\n' } }
            genericArtifacts {
                references { motd { fromFile writeDoc.map { doc.get() } } }
            }
            application { mainClass = 'com.example.Check' }
        """
        def src = new File(dir, 'src/main/java/com/example/Check.java')
        src.parentFile.mkdirs()
        // The static method ref captures no Project; a runtime equality check proves the text block
        // reproduces the document exactly: leading indentation kept and the trailing newline of a
        // multi-line document preserved.
        src << '''
            package com.example;
            public class Check {
                public static void main(String[] args) {
                    if (!FixtureReferences.MOTD.equals("  line1\\nline2\\n")) {
                        throw new AssertionError("unexpected value: [" + FixtureReferences.MOTD + "]");
                    }
                }
            }
        '''

        when: 'running compiles the generated interface and asserts its value'
        def result = runner(dir, 'run').build()

        then:
        result.task(':generateArtifactReferences').outcome == SUCCESS
        result.task(':run').outcome == SUCCESS

        and: 'the generated source uses a text block, not an escaped one-liner'
        def generated = new File(dir,
                'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java').text
        generated.contains('public static final String MOTD = FixtureReferencesLoader.load("MOTD", """')
        !generated.contains('\\nline2')
    }

    /**
     * Writes a `fixture` project (group `com.example`) generating `FixtureReferences.APP` with the
     * default `example/app:1.0`, plus an application `Check` main that asserts `APP` equals its first
     * program argument. {@code runConfig} is injected into the build to configure the `run` task
     * (system properties / program args); callers add override resources or files as needed.
     */
    private void appOverrideProject(String runConfig) {
        new File(dir, 'settings.gradle') << "rootProject.name='fixture'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'java'; id 'application'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            genericArtifacts {
                references { app { value 'example/app:1.0' } }
            }
            application { mainClass = 'com.example.Check' }
            ${runConfig}
        """
        def src = new File(dir, 'src/main/java/com/example/Check.java')
        src.parentFile.mkdirs()
        src << '''
            package com.example;
            public class Check {
                public static void main(String[] args) {
                    if (!FixtureReferences.APP.equals(args[0])) {
                        throw new AssertionError("expected [" + args[0] + "] but was [" + FixtureReferences.APP + "]");
                    }
                }
            }
        '''
    }

    def "a system-property override file replaces the generated value at runtime"() {
        given: 'the run task points the .overrides system property at a properties file'
        appOverrideProject("""
            tasks.named('run') {
                systemProperty 'com.example.FixtureReferences.overrides', file('overrides.properties').absolutePath
                args 'overridden/value'
            }
        """)
        new File(dir, 'overrides.properties') << 'APP=overridden/value\n'

        when:
        def result = runner(dir, 'run').build()

        then: 'Check sees the overridden value, not the generated default'
        result.task(':run').outcome == SUCCESS
    }

    def "a classpath resource overrides the generated value at runtime"() {
        given: 'a flat, dot-named properties resource on the classpath, no system property'
        appOverrideProject("tasks.named('run') { args 'from/classpath' }")
        def res = new File(dir, 'src/main/resources/com.example.FixtureReferences.properties')
        res.parentFile.mkdirs()
        res << 'APP=from/classpath\n'

        when:
        def result = runner(dir, 'run').build()

        then:
        result.task(':run').outcome == SUCCESS
    }

    def "a system-property file wins over a classpath resource for the same key"() {
        given: 'the same key set in both sources'
        appOverrideProject("""
            tasks.named('run') {
                systemProperty 'com.example.FixtureReferences.overrides', file('overrides.properties').absolutePath
                args 'from/sysprop'
            }
        """)
        def res = new File(dir, 'src/main/resources/com.example.FixtureReferences.properties')
        res.parentFile.mkdirs()
        res << 'APP=from/classpath\n'
        new File(dir, 'overrides.properties') << 'APP=from/sysprop\n'

        when:
        def result = runner(dir, 'run').build()

        then: 'the system-property file takes precedence'
        result.task(':run').outcome == SUCCESS
    }

    def "a missing override file is ignored and the generated default is used"() {
        given: 'the system property points at a non-existent file and there is no resource'
        appOverrideProject("""
            tasks.named('run') {
                systemProperty 'com.example.FixtureReferences.overrides', file('does-not-exist.properties').absolutePath
                args 'example/app:1.0'
            }
        """)

        when:
        def result = runner(dir, 'run').build()

        then: 'Check sees the generated default, silently'
        result.task(':run').outcome == SUCCESS
    }

    def "a malformed override file warns and falls back to the generated default"() {
        given: 'an override file with a malformed unicode escape'
        appOverrideProject("""
            tasks.named('run') {
                systemProperty 'com.example.FixtureReferences.overrides', file('overrides.properties').absolutePath
                args 'example/app:1.0'
            }
        """)
        // A bad \\uXXXX escape makes Properties.load throw; the loader catches it and keeps the default.
        new File(dir, 'overrides.properties') << 'APP=\\uXYZW\n'

        when:
        def result = runner(dir, 'run').build()

        then: 'the build still succeeds and Check sees the default'
        result.task(':run').outcome == SUCCESS
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
