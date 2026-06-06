package io.github.nhwalker.artifacts.gradle

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsAttributes
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * Unit tests for the variant-aware generic-artifact wiring: attribute schema,
 * per-declaration consumable/resolvable configurations, the producer→artifact
 * build-dependency link, and the publishable software component. Uses
 * ProjectBuilder and forces the configuration lifecycle with evaluate() so the
 * plugin's afterEvaluate reaction runs.
 */
class ArtifactsPluginSpec extends Specification {

    def project = ProjectBuilder.builder().build()

    def setup() {
        project.pluginManager.apply(ArtifactsPlugin)
        project.group = 'com.example'
        project.version = '1.0'
    }

    private void evaluate() {
        ((ProjectInternal) project).evaluate()
    }

    def "registers the core artifact attributes in the schema"() {
        expect:
        project.dependencies.attributesSchema.hasAttribute(ArtifactsAttributes.ECOSYSTEM)
        project.dependencies.attributesSchema.hasAttribute(ArtifactsAttributes.CLASSIFIER)
    }

    def "a producer creates a consumable carrying ecosystem/classifier + free attributes and a component variant"() {
        given:
        def producing = project.tasks.register('makeReport') {
            outputs.file(project.layout.buildDirectory.file('report.html'))
        }
        project.genericArtifacts {
            produce {
                report {
                    attribute 'flavor', 'html'
                    artifact project.layout.buildDirectory.file('report.html'), { builtBy producing }
                }
            }
        }

        when:
        evaluate()

        then:
        def cfg = project.configurations.getByName('reportElements')
        cfg.canBeConsumed && !cfg.canBeResolved && !cfg.canBeDeclared
        cfg.attributes.getAttribute(ArtifactsAttributes.ECOSYSTEM) == ArtifactsAttributes.ECOSYSTEM_VALUE
        cfg.attributes.getAttribute(ArtifactsAttributes.CLASSIFIER) == 'report'
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute('flavor')) == 'html'

        and: 'the free attribute key is registered in the schema'
        project.dependencies.attributesSchema.hasAttribute(ArtifactsAttributes.freeAttribute('flavor'))

        and: 'an outgoing artifact carries the classifier'
        def artifacts = cfg.outgoing.artifacts.toList()
        artifacts.size() == 1
        artifacts[0].classifier == 'report'

        and: 'no custom outgoing capability (identity stays at the project coordinate)'
        cfg.outgoing.capabilities.isEmpty()

        and: 'the component exists and is adhoc'
        project.components.findByName('genericArtifacts') instanceof AdhocComponentWithVariants
    }

    def "the outgoing artifact records the producing task as a build dependency"() {
        given:
        project.tasks.register('makeReport') {
            outputs.file(project.layout.buildDirectory.file('report.html'))
        }
        project.genericArtifacts {
            produce {
                report {
                    artifact project.layout.buildDirectory.file('report.html'), { builtBy 'makeReport' }
                }
            }
        }

        when:
        evaluate()

        then:
        def artifact = project.configurations.getByName('reportElements').outgoing.artifacts.toList()[0]
        artifact.buildDependencies.getDependencies(null)*.name.contains('makeReport')
    }

    def "the classifier defaults to the element name and can be overridden"() {
        given:
        project.genericArtifacts {
            produce {
                report { artifact project.file('a.txt') }
                bundle { classifier = 'dist'; artifact project.file('b.txt') }
            }
        }

        when:
        evaluate()

        then:
        project.configurations.getByName('reportElements')
                .attributes.getAttribute(ArtifactsAttributes.CLASSIFIER) == 'report'
        project.configurations.getByName('bundleElements')
                .attributes.getAttribute(ArtifactsAttributes.CLASSIFIER) == 'dist'
    }

    def "a producer with no artifacts is rejected"() {
        given:
        project.genericArtifacts { produce { empty { attribute 'k', 'v' } } }

        when:
        evaluate()

        then:
        def e = thrown(Exception)
        // afterEvaluate failures are wrapped by Gradle; the message is preserved.
        e.message.contains('empty') || e.cause?.message?.contains('empty')
    }

    def "a consumer creates a dependency bucket and a classifier-requesting resolvable, exposing files"() {
        given:
        project.genericArtifacts {
            consume {
                theReport {
                    from 'com.example:producer:1.0'
                    classifier = 'report'
                    attribute 'flavor', 'html'
                }
            }
        }

        when:
        evaluate()

        then:
        def bucket = project.configurations.getByName('theReportDeps')
        !bucket.canBeConsumed && !bucket.canBeResolved && bucket.canBeDeclared
        bucket.dependencies.find { it.group == 'com.example' && it.name == 'producer' } != null

        and:
        def resolvable = project.configurations.getByName('theReportRefs')
        resolvable.canBeResolved && !resolvable.canBeConsumed
        resolvable.attributes.getAttribute(ArtifactsAttributes.ECOSYSTEM) == ArtifactsAttributes.ECOSYSTEM_VALUE
        resolvable.attributes.getAttribute(ArtifactsAttributes.CLASSIFIER) == 'report'
        resolvable.attributes.getAttribute(ArtifactsAttributes.freeAttribute('flavor')) == 'html'

        and: 'the DSL files view is backed by the resolvable'
        project.genericArtifacts.consume.theReport.files instanceof org.gradle.api.file.FileCollection
    }
}
