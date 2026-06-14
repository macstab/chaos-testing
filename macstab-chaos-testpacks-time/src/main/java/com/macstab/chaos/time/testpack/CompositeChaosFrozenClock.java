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
 * <p>Simulates a completely frozen system clock by injecting a very large negative offset on every
 * {@code clock_gettime} call across all clocks — effectively pinning the apparent time to a fixed
 * point far in the past. From the application's perspective, time has stopped: every successive
 * call to {@code clock_gettime} returns the same epoch-relative value because the injected delta
 * always cancels out the real time advancement.
 *
 * <h2>How it is created</h2>
 *
 * <p>Applies one libchaos-time rule: {@code clock_gettime:OFFSET:-<epoch_ms>} using the current
 * epoch in milliseconds as the negative delta. Since the epoch advances in real time and the offset
 * is fixed at rule-application time, successive reads show only tiny advances (the amount of real
 * time elapsed since rule application) rather than absolute wall time — modelling the behaviour of
 * a clock that stopped.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * A frozen clock is one of the most dangerous time anomalies for distributed systems. Every
 * deadline, TTL, heartbeat interval, and lease-renewal timer is computed relative to a clock that
 * never advances. Consequences include: distributed locks that never expire, causing deadlocks when
 * the lock holder crashes; Raft elections that never fire because follower election-timeout timers
 * use a frozen baseline; JVM thread-pool keep-alive threads that treat idle connections as
 * brand-new. Recovery requires manual detection because the system appears healthy from the outside
 * (it accepts connections) while its internal timing model is broken.
 *
 * <h2>Industry references</h2>
 *
 * <p>Clock freeze is a documented AWS EC2 failure mode during hypervisor maintenance windows where
 * the guest's virtual TSC is paused while the host performs live migration. The Jepsen test suite
 * includes clock-freeze scenarios as a standard distributed-systems correctness check. TiKV's test
 * harness injects frozen clocks to verify Raft safety under time anomalies.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @ZookeeperCluster
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @CompositeChaosFrozenClock
 * class FrozenClockSafetyTest {
 *
 *   @Test
 *   void sessionExpiry fires even when clock is frozen() {
 *     // assert ZooKeeper session expiry uses server-side monotonic time, not client clock
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosFrozenClock.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.time.testpack.composers.FrozenClockComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosFrozenClock {

  /**
   * Container id to target. Empty string (the default) applies the scenario to every container
   * prepared with libchaos-time.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosFrozenClock[] value();
  }
}
