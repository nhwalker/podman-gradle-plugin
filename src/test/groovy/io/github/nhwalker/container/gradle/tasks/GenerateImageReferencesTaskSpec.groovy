package io.github.nhwalker.container.gradle.tasks

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for the naming conventions used when generating the
 * {@code <ProjectName>Images} interface.
 */
class GenerateImageReferencesTaskSpec extends Specification {

    @Unroll
    def "interface name '#projectName' -> '#expected'"() {
        expect:
        GenerateImageReferencesTask.interfaceName(projectName) == expected

        where:
        projectName            | expected
        'app'                  | 'AppImages'
        'podman-gradle-plugin' | 'PodmanGradlePluginImages'
        'my_service'           | 'MyServiceImages'
        '2cool'                | '_2coolImages'
    }

    @Unroll
    def "constant name '#imageName' -> '#expected'"() {
        expect:
        GenerateImageReferencesTask.constantName(imageName) == expected

        where:
        imageName     | expected
        'app'         | 'APP'
        'webServer'   | 'WEB_SERVER'
        'web-server'  | 'WEB_SERVER'
        'my.image'    | 'MY_IMAGE'
        'image2'      | 'IMAGE2'
        '2nd'         | '_2ND'
    }
}
