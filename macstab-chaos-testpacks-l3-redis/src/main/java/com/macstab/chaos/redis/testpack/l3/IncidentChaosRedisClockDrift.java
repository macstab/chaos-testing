/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates clock drift between the application node and the Redis node, reproducing TTL
 * corruption, premature key expiry, and CAS failure storms from {@code WATCH()} / {@code EVAL}
 * scripts that rely on consistent wall-clock ordering.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Time: REALTIME offset of {@code skewMs} ms at probability {@code probability} — shifts
 *       the application clock forward, causing TTL calculations and script deadlines to diverge
 *       from Redis server time
 *   <li>Connection: SEND latency of {@code latencyMs} ms — adds wire delay that amplifies
 *       apparent skew for time-sensitive MULTI/EXEC and WATCH() transactions
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Moderate</strong><br>Keys expire earlier than expected from the
 * application's perspective; WATCH()-guarded transactions see elevated CAS failure rates,
 * leading to retry amplification in high-concurrency pipelines.
 *
 * <h2>Industry references</h2>
 * <p>Redis EVAL script TTL drift and WATCH() CAS failures under clock skew are documented in
 * the Redis FAQ and observed in distributed systems where NTP is not tightly configured —
 * common in containerised deployments running on shared hypervisors.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.TIME, LibchaosLib.NET})
 * @IncidentChaosRedisClockDrift(skewMs = 1000L, latencyMs = 30L)
 * class RedisClockDriftTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosRedisClockDrift.List.class)
@ChaosL3(composer = "com.macstab.chaos.redis.testpack.l3.composers.RedisClockDriftComposer", severity = Severity.MODERATE)
public @interface IncidentChaosRedisClockDrift {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Milliseconds by which the realtime clock is skewed forward. */
    long skewMs() default 500L;

    /** Probability (0.0–1.0) that the clock offset is applied to a given call. */
    double probability() default 1.0;

    /** Milliseconds of latency injected into every SEND syscall. */
    long latencyMs() default 20L;

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosRedisClockDrift[] value();
    }
}
