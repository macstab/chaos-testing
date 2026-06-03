/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates a Redis Sentinel election storm: replica promotion races with an in-flight NTP
 * clock correction, causing clients to see connection-refused errors, transient DNS resolution
 * failures, and a wall-clock jump that invalidates Sentinel quorum timers simultaneously.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: CONNECT → ECONNREFUSED at configured toxicity — rejects new client connections
 *       while the new primary is not yet accepting
 *   <li>DNS: EAI_AGAIN on every forward lookup — Sentinel member address re-resolution transiently
 *       fails during IP rebinding
 *   <li>Time: REALTIME clock skew forward by {@code clockSkewMs} ms — NTP correction during
 *       failover corrupts Sentinel timeout arithmetic and WAIT() deadline calculations
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Critical</strong><br>All clients lose connectivity for the duration of
 * the election window; retry storms and split-brain windows are common side effects.
 *
 * <h2>Industry references</h2>
 * <p>Redis Sentinel election storms combined with NTP corrections: reported by multiple
 * high-traffic deployments where clock jumps caused Sentinel quorum timers to fire simultaneously,
 * triggering re-elections on an already-recovering cluster.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS, LibchaosLib.TIME})
 * @IncidentChaosRedisFailoverStorm(toxicity = 0.8, clockSkewMs = 5000L)
 * class RedisFailoverTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosRedisFailoverStorm.List.class)
@ChaosL3(composer = "com.macstab.chaos.redis.testpack.l3.composers.RedisFailoverStormComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosRedisFailoverStorm {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
    double toxicity() default 0.5;

    /** Milliseconds by which the realtime clock is shifted forward. */
    long clockSkewMs() default 3000L;

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosRedisFailoverStorm[] value();
    }
}
