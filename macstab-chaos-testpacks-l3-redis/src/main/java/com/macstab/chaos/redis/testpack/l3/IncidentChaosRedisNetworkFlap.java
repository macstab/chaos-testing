/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a Redis Sentinel election storm caused by rapid TCP reset cycling: both CONNECT and
 * RECV operations are hit with ECONNRESET at high toxicity, causing master to appear to change
 * every 200ms as Sentinel sees repeated connectivity loss.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: CONNECT → ECONNRESET at {@code toxicity} — new connections are abruptly reset,
 *       triggering Sentinel election on every connect attempt
 *   <li>Connection: RECV → ECONNRESET at {@code toxicity} — established connections are cut mid-
 *       stream, causing clients to see master change and re-resolve the Sentinel topology
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Clients see master change every 200ms; write chaos ensues as every in-flight command needs to be
 * retried against the newly elected primary.
 *
 * <h2>Industry references</h2>
 *
 * <p>Rapid TCP reset cycling under Sentinel election storms has been observed in high-throughput
 * Redis deployments where network instability triggers cascading promotions faster than clients can
 * converge on a stable master address.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosRedisNetworkFlap(toxicity = 0.9)
 * class RedisNetworkFlapTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosRedisNetworkFlap.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.redis.testpack.l3.composers.RedisNetworkFlapComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosRedisNetworkFlap {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of CONNECT and RECV syscalls that return ECONNRESET (0.0–1.0). */
  double toxicity() default 0.9;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosRedisNetworkFlap[] value();
  }
}
