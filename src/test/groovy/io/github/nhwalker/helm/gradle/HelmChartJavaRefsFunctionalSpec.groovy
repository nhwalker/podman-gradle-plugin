package io.github.nhwalker.helm.gradle

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import java.util.zip.ZipFile

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * Functional tests for the generated {@code <ProjectName>Charts} interface and chart
 * bundling: enabled when a Java plugin is applied and {@code generateJavaRefs} is set,
 * it bundles each packaged chart into the jar at {@code charts/<chart>.tgz}, exposes
 * that resource path as a constant, refreshes when a chart is packaged, compiles with
 * the project's sources, and is wired into the eclipse classpath.
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

    def "generates the charts interface when a chart is packaged"() {
        given:
        writeChart('api')
        writeChart('webProxy')
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                generateJavaRefs = true
                charts { api { lint = false }; webProxy { lint = false } }
            }
        """

        when:
        def result = runner('packageApiChart').build()

        then: 'packaging a chart triggers generation'
        result.task(':packageApiChart').outcome == SUCCESS
        result.task(':generateChartReferences').outcome == SUCCESS

        and: 'the interface exposes each chart jar resource path'
        def generated = new File(dir,
                'build/generated/sources/helmChartRefs/java/main/com/example/FixtureCharts.java')
        generated.exists()
        def text = generated.text
        text.contains('package com.example;')
        text.contains('public interface FixtureCharts')
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
                generateJavaRefs = true
                charts { api { lint = false } }
            }
        """

        when:
        def result = runner('jar').build()

        then:
        result.task(':packageApiChart').outcome == SUCCESS
        result.task(':jar').outcome == SUCCESS

        and: 'the jar carries the chart at the resource path the interface points to'
        def jar = new File(dir, 'build/libs/fixture.jar')
        jar.exists()
        new ZipFile(jar).withCloseable { it.getEntry('charts/api.tgz') != null }
    }

    def "the generated interface is compiled with the project's main sources"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                generateJavaRefs = true
                charts { api { lint = false } }
            }
        """
        def src = new File(dir, 'src/main/java/com/example/Consumer.java')
        src.parentFile.mkdirs()
        src << """
            package com.example;
            public class Consumer {
                public static final String CHART = FixtureCharts.API;
            }
        """

        when:
        def result = runner('compileJava').build()

        then:
        result.task(':generateChartReferences').outcome == SUCCESS
        result.task(':compileJava').outcome == SUCCESS
    }

    def "eclipseClasspath packages the charts and generates the interface"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'java'; id 'eclipse'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                generateJavaRefs = true
                charts { api { lint = false } }
            }
        """

        when:
        def result = runner('eclipseClasspath').build()

        then:
        result.task(':packageApiChart').outcome == SUCCESS
        result.task(':generateChartReferences').outcome == SUCCESS
        result.task(':eclipseClasspath').outcome == SUCCESS

        and:
        new File(dir,
                'build/generated/sources/helmChartRefs/java/main/com/example/FixtureCharts.java').exists()
        new File(dir, '.classpath').text.contains('build/generated/sources/helmChartRefs/java/main')
    }

    def "no interface is generated unless generateJavaRefs is enabled"() {
        given:
        writeChart('api')
        buildFile << """
            plugins { id 'java'; id 'io.github.nhwalker.helm' }
            group = 'com.example'
            helm {
                executable = '${fakeBin.absolutePath}'
                charts { api { lint = false } }
            }
        """

        when:
        def result = runner('packageApiChart').build()

        then:
        result.task(':packageApiChart').outcome == SUCCESS
        result.task(':generateChartReferences') == null
        !new File(dir, 'build/generated/sources/helmChartRefs').exists()
    }
}
