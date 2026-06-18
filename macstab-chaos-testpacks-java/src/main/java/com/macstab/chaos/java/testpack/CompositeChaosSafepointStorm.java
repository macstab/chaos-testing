/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Triggers a rapid sequence of JVM safepoints by invoking {@code System.gc()} (or an equivalent
 * safepoint-forcing primitive) every {@link #intervalMs()} milliseconds, saturating the safepoint
 * mechanism and causing stop-the-world pauses far more frequently than a typical GC cycle.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code SAFEPOINT_STORM} stressor via the JVM chaos agent. The agent's stressor
 * thread issues repeated safepoint requests at the configured interval. In production, safepoint
 * storms arise from dynamic class unloading, biased-lock revocation cascades, or monitoring tools
 * that trigger heap snapshots at high frequency.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Every safepoint pauses all application threads simultaneously. At 50 ms intervals, the
 * application can be paused 20 times per second. Throughput collapses and latency tail explodes.
 * Services relying on timeouts shorter than the accumulated pause time will miss them.
 *
 * <h2>Industry references</h2>
 *
 * <p>Safepoint mechanics are documented in the HotSpot internals: Nitsan Wakart, "Safepoints:
 * Meaning, Side Effects and Overheads" (2015). The -{@code XX:+PrintSafepointStatistics} flag
 * exposes safepoint frequency and pause duration in logs.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosSafepointStorm(intervalMs = 50)
 * class SafepointStormTest {
 *   @Test
 *   void timeoutsAreRespectedUnderSafepointStorm(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosSafepointStorm.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.SafepointStormComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosSafepointStorm {

  /**
   * Interval between safepoint-forcing calls, in milliseconds. Lower values produce more pauses.
   *
   * @return interval in ms; default 50
   */
  long intervalMs() default 50L;

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosSafepointStorm[] value();
  }
}
