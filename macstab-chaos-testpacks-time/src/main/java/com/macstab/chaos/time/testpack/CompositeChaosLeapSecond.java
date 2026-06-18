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
 * <p>Simulates an IERS leap-second insertion by applying a deterministic +1000 ms offset on {@code
 * CLOCK_REALTIME}. During a real leap second the UTC clock pauses at 23:59:60 (or is slewed by
 * NTP); this annotation models the equivalent wall-clock bump that application code observes: wall
 * time suddenly appears one second ahead of what elapsed real-time would predict.
 *
 * <h2>How it is created</h2>
 *
 * <p>Applies one libchaos-time rule: {@code clock_gettime/realtime:OFFSET:+1000}. The fixed 1000 ms
 * value is canonical — the IERS only ever inserts whole leap seconds. Probability is 1.0; every
 * {@code clock_gettime(CLOCK_REALTIME)} call inside the container sees the injected second.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * A single injected leap second is a well-known source of production incidents. The 2012 and 2016
 * leap seconds caused JVM spin-loops (Linux {@code hrtimer} hops back by one second, triggering a
 * busy-wait in {@code Thread.sleep}), Cassandra timeout storms, and Hadoop cluster instability.
 * Services that smooth the adjustment ("leap smearing") recover gracefully; services that trust raw
 * {@code CLOCK_REALTIME} for scheduling or TTL computation do not.
 *
 * <h2>Industry references</h2>
 *
 * <p>The 2012 leap-second incident is documented in numerous post-mortems (Reddit, LinkedIn,
 * Mozilla). Google's "Leap Smear" practice (distributing the extra second over a 20-hour window) is
 * the industry response. The IERS Bulletin C is the normative source of leap-second schedules.
 * Java's {@code Instant} uses UTC-SLS (smoothed leap seconds) by default since Java 8, but many
 * applications still read {@code CLOCK_REALTIME} via JNI or native calls.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @CompositeChaosLeapSecond
 * class LeapSecondResilienceTest {
 *
 *   @Test
 *   void schedulerDoesNotSpinOnLeapSecond(ConnectionInfo info) {
 *     // assert no busy-wait or timeout storm during the simulated leap-second window
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosLeapSecond.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.time.testpack.composers.LeapSecondComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosLeapSecond {

  /**
   * Probability in {@code (0.0, 1.0]} that a given {@code clock_gettime(CLOCK_REALTIME)} call
   * returns the leap-second-offset value. Defaults to {@code 1.0} — every read sees the +1 s jump.
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
    CompositeChaosLeapSecond[] value();
  }
}
