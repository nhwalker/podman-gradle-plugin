package io.github.nhwalker.helm.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import java.util.zip.ZipFile

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Functional tests for bundling charts into resources and the generated
 * {@code <ProjectName>References} interface. Charts opt into bundling with
 * {@code importResourcesTask()} (mirroring the generic artifacts DSL): the chart lands in the jar
 * at {@code charts/<chart>.tgz}, and when {@code generateReferences} is on that path is exposed as
 * a constant, compiled with the project's sources and wired onto the eclipse classpath.
 */
class HelmChartJavaRefsFunctionalSpec extends Specification {

    @TempDir
    File dir

    File buildFile
    File fakeBin

    def setup() {
        buildFile = new File(dir, 'build.gradle')
        new File(dir, 'settings.gradle') << "rootProject.name = 'fixture'\n"

        // A fake helm that, for `package`, drops a .tgz into the --destination dir.
        fakeBin = new File(dir, 'fake-helm')
        fakeBin << """#!/usr/bin/env sh
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
        fakeBin.setExecutable(true)
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
                .withProjectDir(dir)
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput()
    }

    private void writeChart(String name) {
        def chartDir = new File(dir, "src/main/helm/${name}")
        chartDir.mkdirs()
        new File(chartDir, 'Chart.yaml') << "apiVersion: v2\nname: ${name}\nversion: 0.0.0\n"
    }

    def "generates the charts interface for charts bundled into resources"() {
        given:
        writeChart('api')
        writeChart('webProxy')
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                charts {
                    api      { lint = false; importResourcesTask() }
                    webProxy { lint = false; importResourcesTask() }
                }
            }
        """

        when:
        def result = runner('generateReferences').build()

        then: 'the charts were packaged (wired) and the interface was generated'
        result.task(':packageApiChart').outcome == SUCCESS
        result.task(':generateReferences').outcome == SUCCESS

        and: 'the interface exposes each bundled chart jar resource path'
        def generated = new File(dir,
                'build/generated/sources/references/java/main/com/example/FixtureReferences.java')
        generated.exists()
        def text = generated.text
        text.contains('package com.example;')
        text.contains('public interface FixtureReferences')
        text.contains('public static final String API = "charts/api.tgz";')
        text.contains('public static final String WEB_PROXY = "charts/webProxy.tgz";')
    }

    def "bundles the packaged chart into the jar under charts/"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                charts { api { lint = false; importResourcesTask() } }
            }
        """

        when:
        def result = runner('jar').build()

        then:
        result.task(':packageApiChart').outcome == SUCCESS
        result.task(':importApiChartResources').outcome == SUCCESS
        result.task(':jar').outcome == SUCCESS

        and: 'the jar carries the chart at the resource path the interface points to'
        def jar = new File(dir, 'build/libs/fixture.jar')
        jar.exists()
        new ZipFile(jar).withCloseable { it.getEntry('charts/api.tgz') != null }
    }

    def "a bundled chart needs no interface unless generateReferences is enabled"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                charts { api { lint = false; importResourcesTask() } }
            }
        """

        when:
        def result = runner('jar').build()

        then: 'the chart is bundled but no references task or interface exists'
        result.task(':importApiChartResources').outcome == SUCCESS
        result.task(':generateReferences') == null
        !new File(dir, 'build/generated/sources/references').exists()
        new ZipFile(new File(dir, 'build/libs/fixture.jar')).withCloseable {
            it.getEntry('charts/api.tgz') != null
        }
    }

    def "the generated interface is compiled with the project's main sources"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                charts { api { lint = false; importResourcesTask() } }
            }
        """
        def src = new File(dir, 'src/main/java/com/example/Consumer.java')
        src.parentFile.mkdirs()
        src << """
            package com.example;
            public class Consumer {
                public static final String CHART = FixtureReferences.API;
            }
        """

        when:
        def result = runner('compileJava').build()

        then:
        result.task(':generateReferences').outcome == SUCCESS
        result.task(':compileJava').outcome == SUCCESS
    }

    def "a chart can target another source set's resources"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                charts { api { lint = false; importResourcesTask('test') } }
            }
        """

        when:
        def result = runner('processTestResources').build()

        then: 'the chart is staged under the test source set resources at charts/'
        result.task(':importApiTestChartResources').outcome == SUCCESS
        new File(dir, 'build/generated/resources/helmCharts/api/test/charts/api.tgz').exists()
        new File(dir, 'build/resources/test/charts/api.tgz').exists()
    }

    def "eclipseClasspath packages the charts and generates the interface"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'java'; id 'eclipse'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                generateReferences = true
                charts { api { lint = false; importResourcesTask() } }
            }
        """

        when:
        def result = runner('eclipseClasspath').build()

        then:
        result.task(':packageApiChart').outcome == SUCCESS
        result.task(':generateReferences').outcome == SUCCESS
        result.task(':importApiChartResources').outcome == SUCCESS
        result.task(':eclipseClasspath').outcome == SUCCESS

        and: 'the generated source folder and the staged chart resource folder are both on the classpath'
        new File(dir,
                'build/generated/sources/references/java/main/com/example/FixtureReferences.java').exists()
        new File(dir, 'build/generated/resources/helmCharts/api/main/charts/api.tgz').exists()
        def classpath = new File(dir, '.classpath').text
        classpath.contains('build/generated/sources/references/java/main')
        classpath.contains('build/generated/resources/helmCharts/api/main')
    }
}
