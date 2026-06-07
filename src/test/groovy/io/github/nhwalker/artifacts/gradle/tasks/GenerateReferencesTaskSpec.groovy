package io.github.nhwalker.artifacts.gradle.tasks

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for the naming conventions used when generating a references interface
 * (e.g. {@code <ProjectName>Charts} / {@code <ProjectName>Artifacts}).
 */
class GenerateReferencesTaskSpec extends Specification {

    @Unroll
    def "pascalCase '#name' -> '#expected'"() {
        expect:
        GenerateReferencesTask.pascalCase(name) == expected

        where:
        name                   | expected
        'app'                  | 'App'
        'podman-gradle-plugin' | 'PodmanGradlePlugin'
        'my_service'           | 'MyService'
        '2cool'                | '_2cool'
    }

    @Unroll
    def "constant name '#name' -> '#expected'"() {
        expect:
        GenerateReferencesTask.constantName(name) == expected

        where:
        name          | expected
        'api'         | 'API'
        'apiServer'   | 'API_SERVER'
        'api-server'  | 'API_SERVER'
        'my.chart'    | 'MY_CHART'
        'chart2'      | 'CHART2'
        '2nd'         | '_2ND'
    }
}
