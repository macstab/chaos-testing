/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a cold-start cache warming failure: after a deployment or restart the cache is
 * empty, so every request falls through to the backend — which was sized for the cached load, not
 * full request volume. The backend becomes overwhelmed, the cache cannot warm up, and the failure
 * becomes self-sustaining. Netflix re-engineered their cold-start flow after this exact incident.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: CONNECT ECONNREFUSED at {@code toxicity} — cache is unreachable during the
 *       warm-up window, forcing every lookup to bypass the cache
 *   <li>Connection: RECV latency ({@code latencyMs} ms at 1.0 toxicity) — surviving connections to
 *       the backend are slow, compounding backend exhaustion
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Once triggered, the failure is self-sustaining: the overloaded backend cannot serve warming
 * requests fast enough for the cache to recover.
 *
 * <h2>Industry references</h2>
 *
 * <p>Netflix publicly described this class of incident and re-engineered their cache warm-up flow
 * with pre-warming and traffic shaping to prevent cold-start backend overload.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosCacheWarmingFailure(toxicity = 0.8, latencyMs = 1500L)
 * class CacheWarmingFailureTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosCacheWarmingFailure.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.cache.testpack.l3.composers.CacheWarmingFailureComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosCacheWarmingFailure {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
  double toxicity() default 0.7;

  /** RECV latency in milliseconds applied to surviving backend connections. */
  long latencyMs() default 1000L;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosCacheWarmingFailure[] value();
  }
}
