package io.github.nhwalker.container.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Functional tests for the generated {@code <ProjectName>Images} interface: it is
 * produced when a Java plugin is applied and {@code generateJavaRefs} is enabled,
 * carries each image's primary tag, refreshes when an image builds, and is compiled
 * as part of the project's main sources.
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

    def "generates the images interface when an image is built"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateJavaRefs = true
                images {
                    app { tags = ['example/app:1.0', 'example/app:latest'] }
                    webServer { tags = ['example/web:2.0'] }
                }
            }
        """

        when:
        def result = runner('buildAppImage').build()

        then: 'the build of an image triggers generation'
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':generateImageReferences').outcome == SUCCESS

        and: 'the interface carries the primary (first) tag of each image'
        def generated = new File(dir,
                'build/generated/sources/containerImageRefs/java/main/com/example/FixtureImages.java')
        generated.exists()
        def text = generated.text
        text.contains('package com.example;')
        text.contains('public interface FixtureImages')
        text.contains('public static final String APP = "example/app:1.0";')
        text.contains('public static final String WEB_SERVER = "example/web:2.0";')
    }

    def "the generated interface is compiled with the project's main sources"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateJavaRefs = true
                images { app { tags = ['example/app:1.0'] } }
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

        when: 'compiling the project resolves the generated interface'
        def result = runner('compileJava').build()

        then:
        result.task(':generateImageReferences').outcome == SUCCESS
        result.task(':compileJava').outcome == SUCCESS
    }

    def "eclipseClasspath builds the images and generates the interface"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'eclipse'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateJavaRefs = true
                images { app { tags = ['example/app:1.0'] } }
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

    def "no interface is generated unless generateJavaRefs is enabled"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                images { app { tags = ['example/app:1.0'] } }
            }
        """

        when:
        def result = runner('buildAppImage').build()

        then: 'the generation task is never registered'
        result.task(':buildAppImage').outcome == SUCCESS
        result.task(':generateImageReferences') == null
        !new File(dir, 'build/generated/sources/containerImageRefs').exists()
    }

    def "container and generic-artifacts plugins generate separate, non-colliding interfaces"() {
        given: 'a project applying both plugins, each opting into references'
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container'; id 'io.github.nhwalker.artifacts' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateJavaRefs = true
                images { app { tags = ['example/app:1.0'] } }
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
                .text.contains('public static final String APP = "example/app:1.0";')
        new File(dir, 'build/generated/sources/genericArtifactRefs/java/main/com/example/FixtureReferences.java')
                .text.contains('public static final String API_BASE_URL = "https://api.example.com";')
    }

    def "the generated interface name is customizable"() {
        given:
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.container' }
            group = 'com.example'
            container {
                executable = '${fakeBin.absolutePath}'
                generateJavaRefs = true
                referencesClassName = 'MyImages'
                images { app { tags = ['example/app:1.0'] } }
            }
        """

        when:
        def result = runner('generateImageReferences').build()

        then: 'the interface uses the overridden name'
        result.task(':generateImageReferences').outcome == SUCCESS
        new File(dir, 'build/generated/sources/containerImageRefs/java/main/com/example/MyImages.java')
                .text.contains('public interface MyImages')
    }
}
