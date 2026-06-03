/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Makes {@code CLOCK_MONOTONIC} appear to run slower than wall time by applying a negative
 * offset of {@link #skewMs} milliseconds to every {@code clock_gettime(CLOCK_MONOTONIC)} call.
 * The monotonic clock still advances (it is not frozen), but it consistently reads lower than
 * the real elapsed time — as if the application's CPU-time accounting were running slow relative
 * to wall time.
 *
 * <h2>How it is created</h2>
 *
 * <p>Applies one libchaos-time rule: {@code clock_gettime/monotonic:OFFSET:-<skewMs>}. Only
 * {@code CLOCK_MONOTONIC} is affected; {@code CLOCK_REALTIME} and all other clocks are untouched.
 * This creates a measurable divergence between {@code System.nanoTime()} (which reads monotonic)
 * and {@code System.currentTimeMillis()} (which reads realtime) — exactly the condition that
 * exercises wall-vs-monotonic assumption bugs.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Mild</strong><br>
 * A slow monotonic clock causes heartbeat-based failure detectors to believe less time has elapsed
 * than actually has. Raft implementations that use {@code CLOCK_MONOTONIC} for election timeouts
 * (e.g. etcd, CockroachDB) may delay triggering an election past the expected window, making the
 * cluster appear healthier than it is under partition. Timeout-budget calculations that use
 * {@code System.nanoTime()} will under-report elapsed time, causing request timeouts to fire late.
 * Services with retry backoff based on monotonic readings will back off less aggressively than
 * intended.
 *
 * <h2>Industry references</h2>
 *
 * <p>The wall-vs-monotonic divergence is a known pitfall described in the Java Language
 * Specification §17.4.4 (happens-before and time), in the Linux man page for {@code clock_gettime}
 * (§NOTES: monotonic clock and suspend), and in Martin Thompson's "Mechanical Sympathy" blog series
 * on TSC drift. Kubernetes liveness probes use monotonic timing internally; a slow monotonic clock
 * can cause probes to pass their deadline check while the actual wall-time budget has expired.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @EtcdCluster
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @CompositeChaosSlowMonotonic(skewMs = 500)
 * class MonotonicDriftTest {
 *
 *   @Test
 *   void electionTimeoutIsNotMissedUnderSlowMonotonicClock() {
 *     // assert that follower eventually times out and triggers election despite slow monotonic
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosSlowMonotonic.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.time.testpack.composers.SlowMonotonicComposer",
    severity = Severity.MILD)
public @interface CompositeChaosSlowMonotonic {

  /**
   * How many milliseconds the monotonic clock appears to lag behind actual elapsed time.
   * A value of {@code 250} means every {@code clock_gettime(CLOCK_MONOTONIC)} call returns a
   * timestamp that is 250 ms less than reality — the clock runs slow. Must be positive.
   * Defaults to 250 ms.
   */
  long skewMs() default 250L;

  /**
   * Probability in {@code (0.0, 1.0]} that a given {@code clock_gettime(CLOCK_MONOTONIC)} call
   * returns the skewed value. Defaults to {@code 1.0} — every monotonic read is affected.
   */
  double probability() default 1.0;

  /**
   * Container id to target. Empty string (the default) applies the scenario to every container
   * prepared with libchaos-time.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosSlowMonotonic[] value();
  }
}
