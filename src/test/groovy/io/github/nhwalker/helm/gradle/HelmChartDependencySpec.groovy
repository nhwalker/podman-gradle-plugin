package io.github.nhwalker.helm.gradle

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsAttributes
import io.github.nhwalker.helm.gradle.dependency.HelmAttributes
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * Unit tests for the variant-aware chart dependency wiring: attribute schema,
 * per-chart tasks/configurations, and the publishable software component. Charts are
 * modeled as generic artifacts, so the variants carry the generic
 * {@code ecosystem}/{@code classifier} attributes plus the helm free attributes. Uses
 * ProjectBuilder and forces the configuration lifecycle with evaluate() so the
 * plugin's afterEvaluate reaction runs.
 */
class HelmChartDependencySpec extends Specification {

    def project = ProjectBuilder.builder().build()

    def setup() {
        project.pluginManager.apply(HelmPlugin)
        project.group = 'com.example'
        project.version = '1.0'
    }

    private void evaluate() {
        ((ProjectInternal) project).evaluate()
    }

    def "registers the generic artifact attributes and the helm free-attribute keys"() {
        expect:
        project.dependencies.attributesSchema.hasAttribute(ArtifactsAttributes.ECOSYSTEM)
        project.dependencies.attributesSchema.hasAttribute(ArtifactsAttributes.CLASSIFIER)
        project.dependencies.attributesSchema
                .hasAttribute(ArtifactsAttributes.freeAttribute(HelmAttributes.CHART_NAME_KEY))
        project.dependencies.attributesSchema
                .hasAttribute(ArtifactsAttributes.freeAttribute(HelmAttributes.CHART_TYPE_KEY))
    }

    def "a chart creates stage + package + lint tasks and a package-elements consumable"() {
        given:
        project.helm { charts { api { } } }

        when:
        evaluate()

        then:
        project.tasks.findByName('stageApiChart') != null
        project.tasks.findByName('packageApiChart') != null
        project.tasks.findByName('lintApiChart') != null

        and:
        def cfg = project.configurations.getByName('apiPackageElements')
        cfg.canBeConsumed && !cfg.canBeResolved && !cfg.canBeDeclared
        cfg.attributes.getAttribute(ArtifactsAttributes.ECOSYSTEM) == ArtifactsAttributes.ECOSYSTEM_VALUE
        cfg.attributes.getAttribute(ArtifactsAttributes.CLASSIFIER) == 'api'
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute(HelmAttributes.CHART_NAME_KEY)) == 'api'
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute(HelmAttributes.CHART_TYPE_KEY)) ==
                HelmAttributes.CHART_TYPE_PACKAGE

        and: 'the outgoing artifact is the packaged chart (tgz) classified <chart>'
        def artifact = cfg.outgoing.artifacts.first()
        artifact.type == 'tgz'
        artifact.classifier == 'api'

        and: 'the project component exists and is adhoc'
        project.components.findByName('helm') instanceof AdhocComponentWithVariants
    }

    def "lint = false omits the lint task"() {
        given:
        project.helm { charts { api { lint = false } } }

        when:
        evaluate()

        then:
        project.tasks.findByName('packageApiChart') != null
        project.tasks.findByName('lintApiChart') == null
    }

    def "from(notation) creates a subchart bucket and a package-requesting resolvable"() {
        given:
        project.helm {
            charts {
                umbrella {
                    from project.dependencies.create('com.example:base:1.0')
                }
            }
        }

        when:
        evaluate()

        then:
        def bucket = project.configurations.getByName('umbrellaDepSubchart0')
        !bucket.canBeConsumed && !bucket.canBeResolved && bucket.canBeDeclared
        bucket.dependencies.find { it.group == 'com.example' && it.name == 'base' } != null

        and: 'the request pins chartType=package but carries no ecosystem fence'
        def resolvable = project.configurations.getByName('umbrellaRefsSubchart0')
        resolvable.canBeResolved && !resolvable.canBeConsumed
        resolvable.attributes.getAttribute(ArtifactsAttributes.freeAttribute(HelmAttributes.CHART_TYPE_KEY)) ==
                HelmAttributes.CHART_TYPE_PACKAGE
        resolvable.attributes.getAttribute(ArtifactsAttributes.ECOSYSTEM) == null
    }

    def "multiple charts coexist with distinct chartName attributes"() {
        given:
        project.helm { charts { foo { }; bar { } } }

        when:
        evaluate()

        then:
        project.configurations.getByName('fooPackageElements')
                .attributes.getAttribute(ArtifactsAttributes.freeAttribute(HelmAttributes.CHART_NAME_KEY)) == 'foo'
        project.configurations.getByName('barPackageElements')
                .attributes.getAttribute(ArtifactsAttributes.freeAttribute(HelmAttributes.CHART_NAME_KEY)) == 'bar'

        and: 'identity stays at the project coordinate (no custom outgoing capability)'
        project.configurations.getByName('fooPackageElements').outgoing.capabilities.isEmpty()
    }
}
