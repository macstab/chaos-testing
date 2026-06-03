/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a rolling-deploy serialization mismatch: old Redis entries written by the previous
 * pod version fail deserialization on the new pod version, causing 100% cache misses until all
 * old entries expire. Tracked as Spring Boot issue #38959.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>JVM: inject {@code java.io.InvalidClassException} ("serialVersionUID mismatch") on
 *       {@code classPattern} at METHOD_EXIT — every cache read on the new pods throws during
 *       deserialization, forcing the call through to the backing store
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Severe</strong><br>100% cache miss on all new pods until old entries expire.
 * The backing store absorbs full production read load for the duration of the TTL window.
 *
 * <h2>Industry references</h2>
 * <p>Spring Boot issue #38959 documents this pattern, triggered during rolling upgrades when
 * cached object graphs change without explicit cache invalidation or versioned key strategies.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosCacheSerializationMismatch(classPattern = "com.example.cache")
 * class CacheSerializationMismatchTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosCacheSerializationMismatch.List.class)
@ChaosL3(composer = "com.macstab.chaos.cache.testpack.l3.composers.CacheSerializationMismatchComposer", severity = Severity.SEVERE)
public @interface IncidentChaosCacheSerializationMismatch {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Class-name prefix used to select the cache deserialization layer for exception injection. */
    String classPattern() default "org.springframework";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosCacheSerializationMismatch[] value();
    }
}
