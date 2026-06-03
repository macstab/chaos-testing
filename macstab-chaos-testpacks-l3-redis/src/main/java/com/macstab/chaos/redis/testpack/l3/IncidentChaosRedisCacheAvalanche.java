/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a Redis cache avalanche: a mass simultaneous key expiry event causes every client
 * request to miss the cache. Latency on new connections slows re-population, while null returns
 * from cache lookup methods flood the backing store with unprotected queries.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: CONNECT latency of {@code latencyMs} ms — new connections to Redis are slow,
 *       delaying cache re-population under load
 *   <li>JVM: {@code replaceReturn()} on methods matching {@code classPattern} — forces cache
 *       lookup methods to return null, simulating 100% cache miss rate at the Java layer
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>The backing store absorbs the full request load with
 * no cache shielding; connection-pool exhaustion and cascading timeouts follow within seconds.
 *
 * <h2>Industry references</h2>
 * <p>Mass Redis key expiry is a well-documented failure mode: keys set with the same TTL (e.g.
 * loaded at startup) expire simultaneously, causing a thundering-herd against the database.
 * Described in Redis documentation and numerous post-mortems from e-commerce black-friday events.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosRedisCacheAvalanche(toxicity = 0.9, latencyMs = 100L)
 * class CacheAvalancheTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosRedisCacheAvalanche.List.class)
@ChaosL3(composer = "com.macstab.chaos.redis.testpack.l3.composers.RedisCacheAvalancheComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosRedisCacheAvalanche {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls subjected to artificial latency (0.0–1.0). */
    double toxicity() default 0.8;

    /** Milliseconds of latency injected into each Redis connection attempt. */
    long latencyMs() default 50L;

    /** Class name prefix used to match cache client methods for null-return injection. */
    String classPattern() default "redis";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosRedisCacheAvalanche[] value();
    }
}
