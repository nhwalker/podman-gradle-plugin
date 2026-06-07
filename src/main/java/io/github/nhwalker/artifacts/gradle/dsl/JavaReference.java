package io.github.nhwalker.artifacts.gradle.dsl;

import javax.inject.Inject;

import org.gradle.api.Named;
import org.gradle.api.provider.Property;

/**
 * A single arbitrary {@code String} constant declared in
 * {@code genericArtifacts { references { } }}.
 *
 * <p>Where a produced artifact's {@code importResourcesTask()} contributes the <em>resource path</em>
 * of a bundled file, a {@code references} entry contributes an arbitrary value of your choosing —
 * an endpoint URL, a schema version, an externally-supplied image coordinate, anything. Each entry
 * becomes a {@code public static final String} on the generated {@code <ProjectName>References}
 * interface, named after the element in UPPER_SNAKE_CASE:
 *
 * <pre>
 * genericArtifacts {
 *     generateReferences = true
 *     references {
 *         apiBaseUrl    { value = 'https://api.example.com' }   // -> API_BASE_URL
 *         schemaVersion { value 'v3' }                          // -> SCHEMA_VERSION
 *     }
 * }
 * </pre>
 *
 * <p>The value is a lazy {@link Property}, so it may be set from a provider (e.g. a task output or
 * another project's resolved coordinate) and is only realized when the interface is generated.
 */
public abstract class JavaReference implements Named {

    private final String name;

    @Inject
    public JavaReference(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * The value of the generated constant (named after this element in UPPER_SNAKE_CASE). Required;
     * may be set lazily from a provider. An empty value contributes no constant.
     */
    public abstract Property<String> getValue();

    /** Sets the constant's {@link #getValue() value}. */
    public void value(String value) {
        getValue().set(value);
    }
}
