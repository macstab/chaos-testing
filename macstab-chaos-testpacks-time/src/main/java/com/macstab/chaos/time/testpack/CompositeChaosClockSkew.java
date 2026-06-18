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
 * <p>Simulates a sudden, sustained wall-clock jump forward by applying a positive {@code OFFSET} on
 * {@code CLOCK_REALTIME}. Applications see a clock that is {@link #skewMs} milliseconds ahead of
 * actual wall time — exactly the leap a node experiences after NTP re-synchronisation corrects a
 * slow drift, or when a VM is live-migrated and the hypervisor re-stamps the guest's TSC.
 *
 * <h2>How it is created</h2>
 *
 * <p>Applies one libchaos-time rule: {@code clock_gettime/realtime:OFFSET:+<skewMs>}. The offset is
 * deterministic (probability 1.0) and applies to every {@code clock_gettime(CLOCK_REALTIME, …)}
 * call inside the container without touching {@code CLOCK_MONOTONIC} — so the wall-vs-monotonic
 * delta becomes measurably wrong, which is the exact condition that exposes assumption bugs.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * A forward clock jump causes distributed locks with absolute-time TTLs (Redis SET EX, etcd leases)
 * to be extended or incorrectly considered fresh. JWT tokens and signed URLs whose {@code exp}
 * claim is compared to wall time may pass validation on the skewed node while they have already
 * expired on peers. Raft leaders may believe their lease is still valid and continue accepting
 * writes after a network partition ends. Services with well-designed clock-tolerance margins (NTP
 * max-offset checks, lease-renewal buffers) recover without operator intervention; those without do
 * not.
 *
 * <h2>Industry references</h2>
 *
 * <p>Clock skew between distributed nodes is a root cause analysed in the "Spanner: Google's
 * Globally-Distributed Database" paper (Corbett et al., 2012, §3.3 TrueTime) and in the AWS SRE
 * post-mortem on the 2011 EBS outage where NTP step corrections contributed to cascading failures.
 * The Netflix Chaos Engineering blog documents clock-skew injection as a first-class resilience
 * exercise.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @CompositeChaosClockSkew(skewMs = 2_000)
 * class DistributedLockSkewTest {
 *
 *   @Test
 *   void lockTtlSurvivesForwardClockSkew(ConnectionInfo info) {
 *     // assert the lock holder does not release the lock prematurely
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosClockSkew.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.time.testpack.composers.ClockSkewComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosClockSkew {

  /**
   * Forward offset applied to {@code CLOCK_REALTIME} on every {@code clock_gettime} call. Must be
   * positive (a forward jump). Defaults to 500 ms — enough to break most distributed lock TTL
   * assumptions while leaving the container otherwise operable.
   *
   * @return skew in milliseconds; positive = clock jumped forward
   */
  long skewMs() default 500L;

  /**
   * Probability in {@code (0.0, 1.0]} that a given {@code clock_gettime} call returns the skewed
   * value. Defaults to {@code 1.0} — every read is affected, giving a stable, reproducible skew.
   * Lower values create intermittent skew that exercises probabilistic time-comparison bugs.
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
    CompositeChaosClockSkew[] value();
  }
}
