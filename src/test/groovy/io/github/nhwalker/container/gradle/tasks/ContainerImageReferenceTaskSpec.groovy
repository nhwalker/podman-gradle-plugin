package io.github.nhwalker.container.gradle.tasks

import spock.lang.Specification

/**
 * Unit tests for the digest-pinned reference derivation in
 * {@link ContainerImageReferenceTask}, focused on stripping the {@code :tag} suffix
 * without mistaking a registry port for a tag.
 */
class ContainerImageReferenceTaskSpec extends Specification {

    def "repositoryWithoutTag strips only a real tag, preserving registry ports"() {
        expect:
        ContainerImageReferenceTask.repositoryWithoutTag(reference) == repository

        where:
        reference                        || repository
        'app:1.0'                        || 'app'
        'app'                            || 'app'
        'example/app:1.0'                || 'example/app'
        'example/app'                    || 'example/app'
        'registry.example.com/app:1.0'   || 'registry.example.com/app'
        'registry:5000/example/app:1.0'  || 'registry:5000/example/app'
        'registry:5000/example/app'      || 'registry:5000/example/app'
        'registry:5000/app'              || 'registry:5000/app'
    }
}
