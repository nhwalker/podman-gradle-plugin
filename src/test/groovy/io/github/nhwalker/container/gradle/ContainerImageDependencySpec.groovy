package io.github.nhwalker.container.gradle

import io.github.nhwalker.container.gradle.dependency.ContainerAttributes
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * Unit tests for the variant-aware image dependency wiring: attribute schema,
 * per-image tasks/configurations, and the publishable software component. Uses
 * ProjectBuilder and forces the configuration lifecycle with evaluate() so the
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

    def "registers the container attributes in the schema"() {
        expect:
        project.dependencies.attributesSchema.hasAttribute(ContainerAttributes.ECOSYSTEM)
        project.dependencies.attributesSchema.hasAttribute(ContainerAttributes.IMAGE_NAME)
        project.dependencies.attributesSchema.hasAttribute(ContainerAttributes.IMAGE_TYPE)
        project.dependencies.attributesSchema.hasAttribute(ContainerAttributes.ARCHIVE_FORMAT)
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
        cfg.attributes.getAttribute(ContainerAttributes.ECOSYSTEM) == ContainerAttributes.ECOSYSTEM_VALUE
        cfg.attributes.getAttribute(ContainerAttributes.IMAGE_NAME) == 'foo'
        cfg.attributes.getAttribute(ContainerAttributes.IMAGE_TYPE) == ContainerAttributes.IMAGE_TYPE_REFERENCE

        and: 'the project component exists and is adhoc'
        project.components.findByName('container') instanceof AdhocComponentWithVariants
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
        cfg.attributes.getAttribute(ContainerAttributes.IMAGE_TYPE) == ContainerAttributes.IMAGE_TYPE_ARCHIVE
        cfg.attributes.getAttribute(ContainerAttributes.ARCHIVE_FORMAT) == ContainerAttributes.ARCHIVE_FORMAT_OCI
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

        and:
        def resolvable = project.configurations.getByName('appBaseImageRefsBASEIMAGE')
        resolvable.canBeResolved && !resolvable.canBeConsumed
        resolvable.attributes.getAttribute(ContainerAttributes.IMAGE_TYPE) == ContainerAttributes.IMAGE_TYPE_REFERENCE
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
                .attributes.getAttribute(ContainerAttributes.IMAGE_NAME) == 'base'
        project.configurations.getByName('appReferenceElements')
                .attributes.getAttribute(ContainerAttributes.IMAGE_NAME) == 'app'

        and: 'the sibling FROM is recorded on the build task without a self-project configuration'
        def buildApp = project.tasks.getByName('buildAppImage')
        buildApp.baseImages.get().size() == 1
        buildApp.baseImages.get()[0].argName.get() == 'BASE_IMAGE'
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
