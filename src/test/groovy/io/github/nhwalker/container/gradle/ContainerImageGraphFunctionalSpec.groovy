package io.github.nhwalker.container.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * Functional tests for cross-project image dependencies: build ordering and
 * base-image (FROM) injection, configuration-cache safety, publishing one module
 * with several image variants, and composite-build dependency substitution.
 *
 * A fake `podman` script logs every invocation (and echoes a digest for
 * `image inspect`) so the full resolution + execution path runs without podman.
 */
class ContainerImageGraphFunctionalSpec extends Specification {

    @TempDir
    File dir

    File argsLog

    /** Writes a fake podman into {@code root} logging to {@code argsLog}; returns it. */
    private File fakeContainer(File root) {
        def bin = new File(root, 'fake-podman')
        bin << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
if [ "\$1" = "image" ] && [ "\$2" = "inspect" ]; then echo "sha256:deadbeef"; fi
if [ "\$1" = "save" ]; then
  prev=""
  for a in "\$@"; do
    if [ "\$prev" = "-o" ]; then : > "\$a"; fi
    prev="\$a"
  done
fi
exit 0
"""
        bin.setExecutable(true)
        return bin
    }

    /** The plugin-under-test classpath as a Groovy `files(...)` literal for buildscript injection. */
    private String pluginClasspathFilesLiteral() {
        def url = getClass().classLoader.getResource('plugin-under-test-metadata.properties')
        def props = new Properties()
        url.withInputStream { props.load(it) }
        def entries = props.getProperty('implementation-classpath').split(File.pathSeparator)
        'files(' + entries.collect { "'${it.replace('\\', '\\\\')}'" }.join(', ') + ')'
    }

    private GradleRunner runner(File projectDir, String... args) {
        GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput()
    }

    def setup() {
        argsLog = new File(dir, 'podman-args.log')
    }

    def "the reference file is a single line of the form tag@digest"() {
        given:
        def fake = fakeContainer(dir)
        new File(dir, 'settings.gradle') << "rootProject.name='ref'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fake.absolutePath}'
                images { app { tags = ['registry.example.com/app:1.0'] } }
            }
        """

        when:
        def result = runner(dir, 'writeAppImageReference').build()

        then: 'a single line: the full coordinate with the digest appended in place'
        result.task(':writeAppImageReference').outcome == SUCCESS
        new File(dir, 'build/container/app/image-ref.txt').readLines() ==
                ['registry.example.com/app:1.0@sha256:deadbeef']
    }

    def "the reference file omits the digest when includeDigest is false"() {
        given:
        def fake = fakeContainer(dir)
        new File(dir, 'settings.gradle') << "rootProject.name='ref'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fake.absolutePath}'
                images { app { tags = ['registry.example.com/app:1.0']; includeDigest = false } }
            }
        """

        when:
        def result = runner(dir, 'writeAppImageReference').build()

        then: 'just the coordinate, still a single line'
        result.task(':writeAppImageReference').outcome == SUCCESS
        new File(dir, 'build/container/app/image-ref.txt').readLines() ==
                ['registry.example.com/app:1.0']
    }

    def "builds base before app and injects the resolved base reference as a build-arg"() {
        given:
        def fake = fakeContainer(dir)
        new File(dir, 'settings.gradle') << "rootProject.name='graph'\ninclude ':base', ':app'\n"

        new File(dir, 'base').mkdirs()
        new File(dir, 'base/build.gradle') << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fake.absolutePath}'
                images { base { tags = ['base:1'] } }
            }
        """
        new File(dir, 'app').mkdirs()
        new File(dir, 'app/build.gradle') << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fake.absolutePath}'
                images { app { tags = ['app:1']; from 'BASE_IMAGE', project(':base') } }
            }
        """

        when:
        def result = runner(dir, ':app:buildAppImage').build()

        then: 'all producer tasks ran, ordered before the consumer build'
        result.task(':base:buildBaseImage').outcome == SUCCESS
        result.task(':base:writeBaseImageReference').outcome == SUCCESS
        result.task(':app:buildAppImage').outcome == SUCCESS
        def order = result.tasks*.path
        order.indexOf(':base:writeBaseImageReference') < order.indexOf(':app:buildAppImage')

        and: 'the base reference was injected into the app build as a --build-arg'
        argsLog.readLines().any { it.startsWith('build ') && it.contains('--build-arg BASE_IMAGE=base:1') }
    }

    def "the image graph is configuration-cache compatible"() {
        given:
        def fake = fakeContainer(dir)
        new File(dir, 'settings.gradle') << "rootProject.name='graph'\ninclude ':base', ':app'\n"
        new File(dir, 'base').mkdirs()
        new File(dir, 'base/build.gradle') << """
            plugins { id 'io.github.nhwalker.container' }
            container { executable = '${fake.absolutePath}'; images { base { tags = ['base:1'] } } }
        """
        new File(dir, 'app').mkdirs()
        new File(dir, 'app/build.gradle') << """
            plugins { id 'io.github.nhwalker.container' }
            container { executable = '${fake.absolutePath}'
                     images { app { tags = ['app:1']; from 'BASE_IMAGE', project(':base') } } }
        """

        when:
        runner(dir, ':app:buildAppImage', '--configuration-cache').build()
        def result = runner(dir, ':app:buildAppImage', '--configuration-cache').build()

        then:
        result.output.contains('Reusing configuration cache.')
        result.task(':app:buildAppImage').outcome == SUCCESS
    }

    def "publishes one module with a variant per image and form, with distinct classifiers"() {
        given:
        def fake = fakeContainer(dir)
        new File(dir, 'settings.gradle') << "rootProject.name='platform'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.container'; id 'maven-publish' }
            group = 'com.example'
            version = '1.0'
            container {
                executable = '${fake.absolutePath}'
                images {
                    foo { tags = ['example/foo:1.0']; createArchive = true }
                    bar { tags = ['example/bar:1.0'] }
                }
            }
            publishing { publications { maven(MavenPublication) { from components.genericArtifacts } } }
        """

        when:
        def result = runner(dir, 'generateMetadataFileForMavenPublication').build()

        then:
        result.task(':generateMetadataFileForMavenPublication').outcome == SUCCESS
        def module = new File(dir, 'build/publications/maven/module.json').text
        module.contains('io.github.nhwalker.container.imageName')
        module.contains('"io.github.nhwalker.container.imageName": "foo"')
        module.contains('"io.github.nhwalker.container.imageName": "bar"')
        module.contains('"io.github.nhwalker.container.imageType": "reference"')
        module.contains('"io.github.nhwalker.container.imageType": "archive"')
    }

    def "the archive is re-saved only when the image content (digest) changes"() {
        given: 'a fake podman whose inspect digest is read from a controllable file'
        def digestFile = new File(dir, 'digest.txt')
        digestFile.text = 'sha256:aaaa'
        def fake = new File(dir, 'fake-podman')
        fake << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
if [ "\$1" = "image" ] && [ "\$2" = "inspect" ]; then cat '${digestFile.absolutePath}'; fi
if [ "\$1" = "save" ]; then
  prev=""
  for a in "\$@"; do
    if [ "\$prev" = "-o" ]; then : > "\$a"; fi
    prev="\$a"
  done
fi
exit 0
"""
        fake.setExecutable(true)

        new File(dir, 'settings.gradle') << "rootProject.name='archive'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fake.absolutePath}'
                images { app { tags = ['app:1']; createArchive = true } }
            }
        """

        when: 'the archive is saved for the first time'
        def first = runner(dir, 'saveAppImage').build()

        then:
        first.task(':saveAppImage').outcome == SUCCESS

        when: 'nothing changed — the image content (digest) is identical'
        def second = runner(dir, 'saveAppImage').build()

        then: 'the reference always refreshes the digest, but the archive is not re-saved'
        second.task(':writeAppImageReference').outcome == SUCCESS
        second.task(':saveAppImage').outcome == UP_TO_DATE

        when: 'the image content changes (a new digest under the same tag)'
        digestFile.text = 'sha256:bbbb'
        def third = runner(dir, 'saveAppImage').build()

        then: 'the archive is re-saved'
        third.task(':saveAppImage').outcome == SUCCESS
    }

    /** A fake podman where `image exists` reports the given refs present (exit 0) and all others absent. */
    private File fakeContainerWithPresent(File root, List<String> present) {
        def checks = present.collect { "  if [ \"\$3\" = '${it}' ]; then exit 0; fi" }.join("\n")
        def bin = new File(root, 'fake-podman')
        bin << """#!/usr/bin/env sh
echo "\$@" >> '${argsLog.absolutePath}'
if [ "\$1" = "image" ] && [ "\$2" = "inspect" ]; then echo "sha256:deadbeef"; fi
if [ "\$1" = "image" ] && [ "\$2" = "exists" ]; then
${checks}
  exit 1
fi
if [ "\$1" = "save" ]; then
  prev=""
  for a in "\$@"; do
    if [ "\$prev" = "-o" ]; then : > "\$a"; fi
    prev="\$a"
  done
fi
exit 0
"""
        bin.setExecutable(true)
        return bin
    }

    def "an archive saves several images into one tar, pulling only the missing members first"() {
        given: 'the sibling tags are present in storage; only the literal upstream image is missing'
        def fake = fakeContainerWithPresent(dir, ['base:1', 'app:1'])
        new File(dir, 'settings.gradle') << "rootProject.name='bundle'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fake.absolutePath}'
                images {
                    base { tags = ['base:1'] }
                    app  { tags = ['app:1'] }
                }
                archives {
                    bundle {
                        image images.base
                        image images.app
                        image 'docker.io/library/alpine:3.20'
                    }
                }
            }
        """

        when:
        def result = runner(dir, 'saveBundleArchive').build()

        then: 'both sibling images are built and the bundle is saved'
        result.task(':buildBaseImage').outcome == SUCCESS
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':saveBundleArchive').outcome == SUCCESS

        and: 'only the absent member is pulled; the present sibling tags are not'
        def lines = argsLog.readLines()
        lines.contains('pull docker.io/library/alpine:3.20')
        !lines.contains('pull base:1')
        !lines.contains('pull app:1')

        and: 'one save bundles every member in declaration order'
        lines.any {
            it.startsWith('save --format oci-archive -o') &&
                    it.trim().endsWith('base:1 app:1 docker.io/library/alpine:3.20')
        }

        and: 'the single combined archive was written'
        new File(dir, 'build/container/archives/bundle/bundle.oci.tar').exists()
    }

    def "pullPolicy always pulls every member; never pulls none"() {
        given:
        def fake = fakeContainerWithPresent(dir, ['base:1'])   // base:1 is present
        new File(dir, 'settings.gradle') << "rootProject.name='bundle'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.container' }
            container {
                executable = '${fake.absolutePath}'
                images { base { tags = ['base:1'] } }
                archives {
                    eager  { image images.base; pullPolicy = 'always' }
                    lazy   { image images.base; pullPolicy = 'never' }
                }
            }
        """

        when: 'always pulls even though base:1 is present'
        runner(dir, 'saveEagerArchive').build()

        then:
        argsLog.readLines().contains('pull base:1')

        when: 'never pulls nothing'
        argsLog.text = ''
        runner(dir, 'saveLazyArchive').build()

        then:
        !argsLog.readLines().any { it.startsWith('pull ') }
    }

    def "publishes the multi-image archive as an archive variant of the aggregate component"() {
        given:
        def fake = fakeContainer(dir)
        new File(dir, 'settings.gradle') << "rootProject.name='platform'\n"
        new File(dir, 'build.gradle') << """
            plugins { id 'io.github.nhwalker.container'; id 'maven-publish' }
            group = 'com.example'
            version = '1.0'
            container {
                executable = '${fake.absolutePath}'
                images { base { tags = ['example/base:1.0'] } }
                archives { bundle { image images.base } }
            }
            publishing {
                publications { maven(MavenPublication) { from components.genericArtifacts } }
                repositories { maven { name = 'repo'; url = layout.buildDirectory.dir('repo') } }
            }
        """

        when:
        def result = runner(dir, 'generateMetadataFileForMavenPublication').build()

        then: 'the module carries the bundle as an imageName=bundle, imageType=archive variant'
        result.task(':generateMetadataFileForMavenPublication').outcome == SUCCESS
        def module = new File(dir, 'build/publications/maven/module.json').text
        module.contains('"io.github.nhwalker.container.imageName": "bundle"')
        module.contains('"io.github.nhwalker.container.imageType": "archive"')
    }

    def "a composite build substitutes an external coordinate with an included project, no substitution rules"() {
        given: 'a standalone producer build (its own dir) addressed by group:name'
        def producer = new File(dir, 'producer')
        producer.mkdirs()
        def fake = fakeContainer(producer)
        def cp = pluginClasspathFilesLiteral()
        new File(producer, 'settings.gradle') << "rootProject.name='base'\n"
        new File(producer, 'build.gradle') << """
            buildscript { dependencies { classpath ${cp} } }
            apply plugin: 'io.github.nhwalker.container'
            group = 'com.example'
            version = '1.0'
            container { executable = '${fake.absolutePath}'; images { base { tags = ['base:1'] } } }
        """

        and: 'a consumer build that includes the producer and depends on the external coordinate'
        def consumer = new File(dir, 'consumer')
        consumer.mkdirs()
        new File(consumer, 'settings.gradle') << "rootProject.name='consumer'\nincludeBuild '../producer'\n"
        new File(consumer, 'build.gradle') << """
            buildscript { dependencies { classpath ${cp} } }
            apply plugin: 'io.github.nhwalker.container'
            group = 'com.example'
            version = '1.0'
            container {
                executable = '${fake.absolutePath}'
                images { app { tags = ['app:1']; from 'BASE_IMAGE', 'com.example:base:1.0' } }
            }
        """

        when: 'no dependencySubstitution block anywhere'
        def result = GradleRunner.create()
                .withProjectDir(consumer)
                .withArguments(':buildAppImage')
                .forwardOutput()
                .build()

        then: 'the consumer built (so substitution resolved) and the base reference was injected'
        result.task(':buildAppImage').outcome == SUCCESS
        // the included producer build ran its base build + reference write (shared fake log)
        argsLog.readLines().any { it.startsWith('build ') && it.contains('-t base:1') }
        argsLog.readLines().any { it.startsWith('build ') && it.contains('--build-arg BASE_IMAGE=base:1') }
    }
}
