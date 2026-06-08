package io.github.nhwalker.container.gradle

import io.github.nhwalker.artifacts.gradle.dependency.ArtifactsAttributes
import io.github.nhwalker.container.gradle.dependency.ContainerAttributes
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * Unit tests for the variant-aware image dependency wiring: attribute schema,
 * per-image tasks/configurations, and the publishable software component. Images are
 * modeled as generic artifacts, so the variants carry the generic
 * {@code ecosystem}/{@code classifier} attributes plus the container free attributes.
 * Uses ProjectBuilder and forces the configuration lifecycle with evaluate() so the
 * plugin's afterEvaluate reaction runs.
 */
class ContainerImageDependencySpec extends Specification {

    def project = ProjectBuilder.builder().build()

    def setup() {
        project.pluginManager.apply(ContainerPlugin)
        project.group = 'com.example'
        project.version = '1.0'
    }

    private void evaluate() {
        ((ProjectInternal) project).evaluate()
    }

    def "registers the generic artifact attributes and the container free-attribute keys"() {
        expect:
        project.dependencies.attributesSchema.hasAttribute(ArtifactsAttributes.ECOSYSTEM)
        project.dependencies.attributesSchema.hasAttribute(ArtifactsAttributes.CLASSIFIER)
        project.dependencies.attributesSchema
                .hasAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_NAME_KEY))
        project.dependencies.attributesSchema
                .hasAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_TYPE_KEY))
        project.dependencies.attributesSchema
                .hasAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.ARCHIVE_FORMAT_KEY))
    }

    def "an image creates build + reference tasks and a reference-elements consumable"() {
        given:
        project.container { images { foo { tags = ['example/foo:1.0'] } } }

        when:
        evaluate()

        then:
        project.tasks.findByName('buildFooImage') != null
        project.tasks.findByName('writeFooImageReference') != null

        and:
        def cfg = project.configurations.getByName('fooReferenceElements')
        cfg.canBeConsumed && !cfg.canBeResolved && !cfg.canBeDeclared
        cfg.attributes.getAttribute(ArtifactsAttributes.ECOSYSTEM) == ArtifactsAttributes.ECOSYSTEM_VALUE
        cfg.attributes.getAttribute(ArtifactsAttributes.CLASSIFIER) == 'foo-reference'
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_NAME_KEY)) == 'foo'
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_TYPE_KEY)) ==
                ContainerAttributes.IMAGE_TYPE_REFERENCE

        and: 'the outgoing artifact is the reference file (txt) classified <image>-reference'
        def artifact = cfg.outgoing.artifacts.first()
        artifact.type == 'txt'
        artifact.classifier == 'foo-reference'

        and: 'the shared aggregate component exists and is adhoc (no per-plugin container component)'
        project.components.findByName('genericArtifacts') instanceof AdhocComponentWithVariants
        project.components.findByName('container') == null
    }

    def "createArchive adds a save task and an archive-elements consumable"() {
        given:
        project.container { images { foo { tags = ['example/foo:1.0']; createArchive = true } } }

        when:
        evaluate()

        then:
        project.tasks.findByName('saveFooImage') != null
        def cfg = project.configurations.getByName('fooArchiveElements')
        cfg.canBeConsumed && !cfg.canBeResolved
        cfg.attributes.getAttribute(ArtifactsAttributes.CLASSIFIER) == 'foo'
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_TYPE_KEY)) ==
                ContainerAttributes.IMAGE_TYPE_ARCHIVE
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.ARCHIVE_FORMAT_KEY)) ==
                ContainerAttributes.ARCHIVE_FORMAT_OCI

        and: 'the outgoing artifact is the saved archive (tar) classified <image>'
        def artifact = cfg.outgoing.artifacts.first()
        artifact.type == 'tar'
        artifact.classifier == 'foo'
    }

    def "from(project) creates a base-image bucket and a reference-requesting resolvable"() {
        given:
        project.container {
            images {
                app {
                    tags = ['example/app:1.0']
                    from 'BASE_IMAGE', project.dependencies.create('com.example:base:1.0')
                }
            }
        }

        when:
        evaluate()

        then:
        def bucket = project.configurations.getByName('appBaseImageDepBASEIMAGE')
        !bucket.canBeConsumed && !bucket.canBeResolved && bucket.canBeDeclared
        bucket.dependencies.find { it.group == 'com.example' && it.name == 'base' } != null

        and: 'the request pins imageType=reference but carries no ecosystem fence'
        def resolvable = project.configurations.getByName('appBaseImageRefsBASEIMAGE')
        resolvable.canBeResolved && !resolvable.canBeConsumed
        resolvable.attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_TYPE_KEY)) ==
                ContainerAttributes.IMAGE_TYPE_REFERENCE
        resolvable.attributes.getAttribute(ArtifactsAttributes.ECOSYSTEM) == null
    }

    def "multiple images coexist with distinct imageName attributes and a sibling FROM is wired"() {
        given:
        project.container {
            images {
                base { tags = ['example/base:1.0'] }
                app {
                    tags = ['example/app:1.0']
                    from 'BASE_IMAGE', images.base
                }
            }
        }

        when:
        evaluate()

        then: 'both images produce non-colliding consumables with their own imageName'
        project.configurations.getByName('baseReferenceElements')
                .attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_NAME_KEY)) == 'base'
        project.configurations.getByName('appReferenceElements')
                .attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_NAME_KEY)) == 'app'

        and: 'the sibling FROM is recorded on the build task without a self-project configuration'
        def buildApp = project.tasks.getByName('buildAppImage')
        buildApp.baseImages.get().size() == 1
        buildApp.baseImages.get()[0].argName.get() == 'BASE_IMAGE'
    }

    def "an archive creates a save task and a bundle-elements archive consumable"() {
        given:
        project.container {
            images { base { tags = ['example/base:1.0'] } }
            archives { bundle { image images.base } }
        }

        when:
        evaluate()

        then: 'a save<Name>Archive task and a consumable bundle-elements config exist'
        project.tasks.findByName('saveBundleArchive') != null
        def cfg = project.configurations.getByName('bundleArchiveBundleElements')
        cfg.canBeConsumed && !cfg.canBeResolved
        cfg.attributes.getAttribute(ArtifactsAttributes.CLASSIFIER) == 'bundle'
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_NAME_KEY)) == 'bundle'
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.IMAGE_TYPE_KEY)) ==
                ContainerAttributes.IMAGE_TYPE_ARCHIVE
        cfg.attributes.getAttribute(ArtifactsAttributes.freeAttribute(ContainerAttributes.ARCHIVE_FORMAT_KEY)) ==
                ContainerAttributes.ARCHIVE_FORMAT_OCI

        and: 'the outgoing artifact is the bundle tar classified <archive>'
        def artifact = cfg.outgoing.artifacts.first()
        artifact.type == 'tar'
        artifact.classifier == 'bundle'
    }

    def "an archive defaultArtifact clears the file classifier but keeps the classifier attribute"() {
        given:
        project.container {
            images { base { tags = ['example/base:1.0'] } }
            archives { bundle { image images.base; defaultArtifact = true } }
        }

        when:
        evaluate()

        then: 'the variant still selects on classifier=bundle, but the published file is unclassified (bare GAV)'
        def cfg = project.configurations.getByName('bundleArchiveBundleElements')
        cfg.attributes.getAttribute(ArtifactsAttributes.CLASSIFIER) == 'bundle'
        cfg.outgoing.artifacts.first().classifier == null
    }

    def "an archive whose name collides with an image is rejected"() {
        given:
        project.container {
            images { foo { tags = ['example/foo:1.0'] } }
            archives { foo { image images.foo } }
        }

        when:
        evaluate()

        then:
        def e = thrown(Exception)
        def root = e
        while (root.cause != null) {
            root = root.cause
        }
        root instanceof org.gradle.api.InvalidUserDataException
        root.message.contains('collides with image')
    }

    def "a multi-image consumable carries the default implicit project capability (no custom capability)"() {
        given:
        project.container { images { foo { tags = ['example/foo:1.0'] }; bar { tags = ['example/bar:1.0'] } } }

        when:
        evaluate()

        then: 'no custom outgoing capability is declared (identity stays at the project coordinate)'
        project.configurations.getByName('fooReferenceElements').outgoing.capabilities.isEmpty()
        project.configurations.getByName('barReferenceElements').outgoing.capabilities.isEmpty()
    }
}
