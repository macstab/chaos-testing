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
 * <p>Sustains a high allocation rate inside the target container's JVM to trigger frequent
 * stop-the-world GC pauses. The stressor allocates short-lived byte arrays at a configurable rate
 * and for a configurable duration, simulating a high-throughput service under peak JSON-processing
 * or event-streaming load.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code GC_PRESSURE} stressor scenario via the JVM chaos agent's plan accumulator,
 * causing the young generation to fill rapidly and triggering minor GCs on each full cycle. In
 * production, this pattern appears in services that generate excessive temporary objects — JSON
 * deserialisation, string concatenation in tight loops, or batch-processing pipelines that do not
 * use streaming APIs.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * The service remains responsive, but latency spikes at GC pause boundaries. P99 and P999 latency
 * will diverge from mean. Requests that arrive during a pause may miss their SLA timeout. The
 * application usually recovers when the stressor stops.
 *
 * <h2>Industry references</h2>
 *
 * <p>GC pause impact on tail latency is documented extensively: Gil Tene's "Understanding Java
 * Garbage Collection" (QCon 2013) shows that a 50 ms GC pause produces a 50 ms latency spike for
 * every concurrent request — not just the one that triggered GC. Netflix's "Garbage Collection
 * Tuning Guide" describes the same effect at scale.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosGcPause(durationMs = 10_000)
 * class GcPauseResilienceTest {
 *   @Test
 *   void p99LatencyStaysWithinSlaUnderGcPressure(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosGcPause.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.GcPauseComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosGcPause {

  /**
   * How long the GC pressure stressor runs, in milliseconds.
   *
   * @return stressor duration in ms; default 500
   */
  long durationMs() default 500L;

  /**
   * Target allocation rate in bytes per second.
   *
   * @return bytes/s; default 100 MB/s
   */
  long allocationRateBytesPerSecond() default 104_857_600L;

  /**
   * Container id to target. Empty string (the default) applies the scenario to every container that
   * has the JVM agent attached.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosGcPause[] value();
  }
}
