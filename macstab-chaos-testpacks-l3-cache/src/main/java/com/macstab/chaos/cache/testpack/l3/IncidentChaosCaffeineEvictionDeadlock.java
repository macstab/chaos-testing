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
 * <p>Simulates Caffeine eviction deadlock (Caffeine issue #672): a single slow cache loader holds
 * the internal {@code evictionLock} for the duration of its load. Any other thread that triggers
 * eviction on an unrelated key blocks on the same lock, causing up to 1,400 threads to wait. Cache
 * throughput drops to zero and a restart is required to recover.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV latency ({@code latencyMs} ms at 1.0 toxicity) — makes one loader slow,
 *       keeping the eviction lock held for the entire wait duration
 *   <li>JVM: inject {@code java.util.concurrent.TimeoutException} ("Caffeine loader timed out") on
 *       {@code classPattern} at METHOD_EXIT — loader fails after holding the lock, amplifying the
 *       thread pile-up
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Cache throughput drops to zero; all threads waiting on unrelated keys are blocked. A process
 * restart is required to recover.
 *
 * <h2>Industry references</h2>
 *
 * <p>Caffeine GitHub issue #672 documents this behaviour: a single slow loader acquires {@code
 * evictionLock} and blocks all eviction-driven operations across the entire cache instance.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosCaffeineEvictionDeadlock(latencyMs = 800L)
 * class CaffeineEvictionDeadlockTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosCaffeineEvictionDeadlock.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.cache.testpack.l3.composers.CaffeineEvictionDeadlockComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosCaffeineEvictionDeadlock {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** RECV latency in milliseconds that keeps the slow loader (and the eviction lock) occupied. */
  long latencyMs() default 500L;

  /** Class-name prefix used to select Caffeine loader methods for exception injection. */
  String classPattern() default "com.github.benmanes.caffeine";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosCaffeineEvictionDeadlock[] value();
  }
}
