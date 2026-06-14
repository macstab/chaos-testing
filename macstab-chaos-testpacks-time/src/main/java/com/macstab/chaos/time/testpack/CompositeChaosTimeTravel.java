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
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a dramatic backward clock jump on {@code CLOCK_REALTIME} — "time travel" into the
 * past. The clock appears to jump backward by {@link #skewMs} milliseconds on every {@code
 * clock_gettime(CLOCK_REALTIME)} call, as if NTP stepped the clock backward after a prolonged drift
 * correction, or a VM was restored from a snapshot taken in the past.
 *
 * <h2>How it is created</h2>
 *
 * <p>Applies one libchaos-time rule: {@code clock_gettime/realtime:OFFSET:-<skewMs>}. The offset is
 * applied deterministically (probability 1.0) to every realtime read. {@code CLOCK_MONOTONIC} is
 * untouched, creating a large negative delta between wall time and monotonic time — the exact
 * condition that breaks code assuming the two move in the same direction.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * A backward wall-clock jump is one of the most common causes of distributed system incidents.
 * Consequences include: JWTs and signed URLs that were issued in the application's future appear
 * immediately expired; Redis TTLs computed from wall time expire instantly because the remaining
 * TTL computes as negative; Cassandra's LWT uses wall time for token comparison and may reject
 * writes from a node whose clock jumped back; Raft lease-based read optimizations fail because the
 * leader's lease expiry appears to be in the past. The default {@link #skewMs} of one hour is
 * chosen to exceed most token validity windows and TTL budgets.
 *
 * <h2>Industry references</h2>
 *
 * <p>NTP step-backward corrections are documented as a root cause in multiple cloud post-mortems.
 * The Linux kernel's {@code timekeeping_inject_offset} path can cause a sudden backward wall-clock
 * correction when the kernel's NTP discipline detects a large accumulated error. Cassandra's
 * CASSANDRA-14702 documents how a backward clock step caused token-ring inconsistencies. The
 * Spanner TrueTime design explicitly anticipates and bounds backward wall-clock steps.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @CompositeChaosTimeTravel(skewMs = 3_600_000)
 * class TimeTravelTest {
 *
 *   @Test
 *   void jwtValidationHandlesBackwardClockJump(ConnectionInfo info) {
 *     // assert that the service does not reject all JWTs as expired after a clock jump backward
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosTimeTravel.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.time.testpack.composers.TimeTravelComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosTimeTravel {

  /**
   * Magnitude of the backward jump applied to {@code CLOCK_REALTIME}. Must be positive here — the
   * composer negates it before building the rule. Defaults to 3 600 000 ms (one hour).
   *
   * @return jump magnitude in milliseconds; the realtime clock will appear this many ms in the past
   */
  long skewMs() default 3_600_000L;

  /**
   * Probability in {@code (0.0, 1.0]} that a given {@code clock_gettime(CLOCK_REALTIME)} call
   * returns the backward-jumped value. Defaults to {@code 1.0} — every realtime read is affected.
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
    CompositeChaosTimeTravel[] value();
  }
}
