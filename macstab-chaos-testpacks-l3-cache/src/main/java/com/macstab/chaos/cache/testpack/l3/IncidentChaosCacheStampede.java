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
 * <p>Simulates a cache stampede: a hot cache key expires and 100–1,000× concurrent requests race to
 * the backing database simultaneously. The resulting DB lock contention prevents the cache from
 * refilling, which causes the next wave of requests to hit the DB again — a death spiral seen at
 * Twitter, Reddit, and Instagram scale.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV latency ({@code latencyMs} ms at {@code toxicity}) — simulates DB slowdown
 *       under thundering-herd load
 *   <li>JVM: corruptReturnValue(NULL) on {@code classPattern} at METHOD_EXIT — forces continuous
 *       cache misses so every request falls through to the backing store
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * DB locks → cache refill fails → death spiral. Recovery requires either a cache warm-up strategy
 * or circuit-breaker intervention.
 *
 * <h2>Industry references</h2>
 *
 * <p>Twitter, Reddit, and Instagram have all published post-mortems describing thundering-herd
 * events triggered by simultaneous expiry of popular cache keys under high read traffic.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosCacheStampede(latencyMs = 3000L, toxicity = 0.95)
 * class CacheStampedeTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosCacheStampede.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.cache.testpack.l3.composers.CacheStampedeComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosCacheStampede {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** RECV latency in milliseconds injected on the backing-store connection. */
  long latencyMs() default 2000L;

  /** Fraction of RECV syscalls that are delayed (0.0–1.0). */
  double toxicity() default 0.9;

  /** Class-name prefix used to select the cache abstraction layer for null-return injection. */
  String classPattern() default "org.springframework.cache";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosCacheStampede[] value();
  }
}
