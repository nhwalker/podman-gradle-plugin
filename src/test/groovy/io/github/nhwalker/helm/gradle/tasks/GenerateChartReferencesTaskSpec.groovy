package io.github.nhwalker.helm.gradle.tasks

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for the naming conventions used when generating the
 * {@code <ProjectName>Charts} interface.
 */
class GenerateChartReferencesTaskSpec extends Specification {

    @Unroll
    def "interface name '#projectName' -> '#expected'"() {
        expect:
        GenerateChartReferencesTask.interfaceName(projectName) == expected

        where:
        projectName            | expected
        'app'                  | 'AppCharts'
        'podman-gradle-plugin' | 'PodmanGradlePluginCharts'
        'my_service'           | 'MyServiceCharts'
        '2cool'                | '_2coolCharts'
    }

    @Unroll
    def "constant name '#chartName' -> '#expected'"() {
        expect:
        GenerateChartReferencesTask.constantName(chartName) == expected

        where:
        chartName     | expected
        'api'         | 'API'
        'apiServer'   | 'API_SERVER'
        'api-server'  | 'API_SERVER'
        'my.chart'    | 'MY_CHART'
        'chart2'      | 'CHART2'
        '2nd'         | '_2ND'
    }
}
