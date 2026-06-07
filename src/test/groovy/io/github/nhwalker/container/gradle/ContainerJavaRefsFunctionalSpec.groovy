package io.github.nhwalker.container.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Functional tests for the generated {@code <ProjectName>Images} interface: it is produced when a
 * Java plugin is applied, {@code generateReferences} is enabled, and an image opts in via
 * {@code javaReference(...)}. Each opted-in image's constant carries the digest-pinned reference read
 * from its reference file, the interface is compiled as part of the chosen source set, and the
 * container-engine dependency is scoped to that source set.
 */
class ContainerJavaRefsFunctionalSpec extends Specification {

    @TempDir
    File dir

    File buildFile
    File fakeBin

    def setup() {
        buildFile = new File(dir, 'build.gradle')
        new File(dir, 'settings.gradle') << "rootProject.name = 'fixture'\n"

        fakeBin = new File(dir, 'fake-podman')
        fakeBin << """#!/usr/bin/env sh
if [ "\$1" = "image" ] && [ "\$2" = "inspect" ]; then echo "sha256:deadbeef"; fi
exit 0
"""
        fakeBin.setExecutable(true)
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
                .withProjectDir(dir)
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput()
    }

    def "generates the images interface for opted-in images, with the digest-pinned reference"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                images {
                    app { tags = ['example/app:1.0', 'example/app:latest']; javaReference() }
                    webServer { tags = ['example/web:2.0']; javaReference() }
                }
            }
        """

        when:
        def result = runner('generateImageReferences').build()

        then: 'the images were built and their references written ahead of generation'
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':writeAppImageReference').outcome == SUCCESS
        result.task(':generateImageReferences').outcome == SUCCESS

        and: 'each constant carries the reference-file contents (tag with the digest appended in place)'
        def generated = new File(dir,
                'build/generated/sources/containerImageRefs/java/main/com/example/FixtureImages.java')
        generated.exists()
        def text = generated.text
        text.contains('package com.example;')
        text.contains('public interface FixtureImages')
        text.contains('public static final String APP = FixtureImagesLoader.load("APP", "example/app:1.0@sha256:deadbeef");')
        text.contains('public static final String WEB_SERVER = FixtureImagesLoader.load("WEB_SERVER", "example/web:2.0@sha256:deadbeef");')
    }

    def "the generated interface is compiled with the project's main sources"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                images { app { tags = ['example/app:1.0']; javaReference() } }
            }
        """
        def src = new File(dir, 'src/main/java/com/example/Consumer.java')
        src.parentFile.mkdirs()
        src << """
            package com.example;
            public class Consumer {
                public static final String IMAGE = FixtureImages.APP;
            }
        """

        when: 'compiling the project resolves (and so generates + builds) the interface'
        def result = runner('compileJava').build()

        then:
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':generateImageReferences').outcome == SUCCESS
        result.task(':compileJava').outcome == SUCCESS
    }

    def "an opted-out image contributes no constant"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                images {
                    app   { tags = ['example/app:1.0'];   javaReference() }
                    other { tags = ['example/other:1.0'] }   // no javaReference()
                }
            }
        """

        when:
        def result = runner('generateImageReferences').build()

        then: 'only the opted-in image is built and only its constant appears'
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':buildOtherImage') == null
        def text = new File(dir,
                'build/generated/sources/containerImageRefs/java/main/com/example/FixtureImages.java').text
        text.contains('public static final String APP =')
        !text.contains('OTHER')
    }

    def "an image exposed only to test keeps the main compilation engine-free"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                images { itBase { tags = ['example/it-base:1.0']; javaReference('test') } }
            }
        """

        when: 'compiling the main sources'
        def main = runner('compileJava').build()

        then: 'the image is never built and neither interface is generated for main'
        main.task(':buildItBaseImage') == null
        main.task(':generateImageReferences') == null
        main.task(':generateTestImageReferences') == null
        !new File(dir, 'build/generated/sources/containerImageRefs/java/test').exists()

        when: 'compiling the test sources'
        def test = runner('compileTestJava').build()

        then: 'the test interface is generated, building + inspecting the image first'
        test.task(':buildItBaseImage').outcome == SUCCESS
        test.task(':writeItBaseImageReference').outcome == SUCCESS
        test.task(':generateTestImageReferences').outcome == SUCCESS

        and: 'the suffixed interface lands in the test source set with the digest-pinned reference'
        new File(dir, 'build/generated/sources/containerImageRefs/java/test/com/example/FixtureImagesTest.java')
                .text.contains('public static final String IT_BASE = FixtureImagesTestLoader.load("IT_BASE", "example/it-base:1.0@sha256:deadbeef");')
    }

    def "eclipseClasspath builds the images and generates the interface"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'eclipse'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                images { app { tags = ['example/app:1.0']; javaReference() } }
            }
        """

        when:
        def result = runner('eclipseClasspath').build()

        then: 'regenerating the classpath builds the image and refreshes the refs'
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':generateImageReferences').outcome == SUCCESS
        result.task(':eclipseClasspath').outcome == SUCCESS

        and: 'the generated source folder is on the eclipse classpath'
        new File(dir,
                'build/generated/sources/containerImageRefs/java/main/com/example/FixtureImages.java').exists()
        new File(dir, '.classpath').text.contains('build/generated/sources/containerImageRefs/java/main')
    }

    def "no interface is generated unless generateReferences is enabled"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                images { app { tags = ['example/app:1.0']; javaReference() } }   // opt-in but switch off
            }
        """

        when:
        def result = runner('buildAppImage').build()

        then: 'the generation task is never registered'
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':generateImageReferences') == null
        !new File(dir, 'build/generated/sources/containerImageRefs').exists()
    }

    def "the generated images interface is configuration-cache compatible"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                images { app { tags = ['example/app:1.0']; javaReference() } }
            }
        """

        when:
        runner('generateImageReferences', '--configuration-cache').build()
        def result = runner('generateImageReferences', '--configuration-cache').build()

        then:
        result.output.contains('Reusing configuration cache.')
        new File(dir, 'build/generated/sources/containerImageRefs/java/main/com/example/FixtureImages.java')
                .text.contains('public static final String APP = FixtureImagesLoader.load("APP", "example/app:1.0@sha256:deadbeef");')
    }

    def "container and generic-artifacts plugins generate separate, non-colliding interfaces"() {
        given: 'a project applying both plugins, each opting into references'
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                images { app { tags = ['example/app:1.0']; javaReference() } }
            }
            genericArtifacts {
                generateReferences = true
                references { apiBaseUrl { value = 'https://api.example.com' } }
            }
        """
        def src = new File(dir, 'src/main/java/com/example/Consumer.java')
        src.parentFile.mkdirs()
        src << """
            package com.example;
            public class Consumer {
                public static final String IMAGE = FixtureImages.APP;
                public static final String URL = FixtureReferences.API_BASE_URL;
            }
        """

        when: 'both interfaces are generated and compiled together'
        def result = runner('compileJava').build()

        then: 'each plugin emits its own domain-named interface, side by side'
        result.task(':generateImageReferences').outcome == SUCCESS
        result.task(':generateArtifactReferences').outcome == SUCCESS
        result.task(':compileJava').outcome == SUCCESS
        new File(dir, 'build/generated/sources/containerImageRefs/java/main/com/example/FixtureImages.java')
                .text.contains('public static final String APP = FixtureImagesLoader.load("APP", "example/app:1.0@sha256:deadbeef");')
        new File(dir, 'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java')
                .text.contains('public static final String API_BASE_URL = FixtureReferencesLoader.load("API_BASE_URL", "https://api.example.com");')
    }

    def "the generated interface name is customizable"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                referencesClassName = 'MyImages'
                images { app { tags = ['example/app:1.0']; javaReference() } }
            }
        """

        when:
        def result = runner('generateImageReferences').build()

        then: 'the interface uses the overridden name'
        result.task(':generateImageReferences').outcome == SUCCESS
        new File(dir, 'build/generated/sources/containerImageRefs/java/main/com/example/MyImages.java')
                .text.contains('public interface MyImages')
    }

    def "a generic reference captures a container image reference via fromFile"() {
        given: 'the container plugin publishes the image reference; the artifacts plugin consumes it'
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                images { app { tags = ['example/app:1.0'] } }   // includeDigest defaults to true
            }
            genericArtifacts {
                generateReferences = true
                consume    { appRef   { from project(':'); classifier = 'app-reference' } }
                references { appImage { fromFile genericArtifacts.consume.appRef.files } }
            }
        """

        when:
        def result = runner('generateArtifactReferences').build()

        then: 'building the image and writing its reference are wired ahead of generation'
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':writeAppImageReference').outcome == SUCCESS
        result.task(':generateArtifactReferences').outcome == SUCCESS

        and: 'the single-line tag@digest reference lands verbatim in the generated constant'
        new File(dir, 'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java')
                .text.contains('public static final String APP_IMAGE = FixtureReferencesLoader.load("APP_IMAGE", "example/app:1.0@sha256:deadbeef");')
    }
}
