/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.clock_gettime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Adds a signed {@link #deltaMs} millisecond offset to every {@code clock_gettime(2)} result,
 * causing the caller to observe a clock that is consistently ahead of or behind wall time.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code CLOCK_GETTIME}, effect = OFFSET)
 * tuple. Unlike errno variants the call succeeds (returns 0); the interposer modifies the
 * {@code timespec} struct in-place by adding {@link #deltaMs} to the nanosecond fields before
 * returning it to the caller. No runtime selector-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code clock_gettime} call, after the real kernel call succeeds, the
 *       interposer adds {@link #deltaMs} milliseconds to the returned {@code tv_sec} / {@code tv_nsec}
 *       fields (carrying nanoseconds correctly into seconds).
 *   <li>The modified {@code timespec} is returned to the application with a {@code 0} return value.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>A negative {@link #deltaMs} rewinds the clock — deadlines that were in the future suddenly
 *       appear to be in the past, causing premature expiry of leases, tokens, and TTLs.
 *   <li>A positive {@link #deltaMs} advances the clock — heartbeats appear to have been sent far
 *       in the future, which can cause Raft followers to grant elections to the wrong node.
 *   <li>Distributed locking implementations (Redission, etcd leases) are the primary targets:
 *       assert that the lock is not released prematurely and that the fencing token is still valid.
 *   <li>JWT expiry, certificate validity windows, and signed-URL expiry are also affected by
 *       wall-clock skew; assert that these checks account for configurable clock drift tolerance.
 * </ul>
 *
 * <p>In production, clock skew between nodes is a real operational hazard: NTP re-sync can jump
 * the wall clock by seconds; PTP hardware timestamps drift when the NIC clock diverges from the
 * CPU TSC; VM live migrations may introduce a burst of skew during the migration pause.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Raft implementations (etcd, CockroachDB's Raft layer) use {@code CLOCK_MONOTONIC} for
 * heartbeat timeouts and {@code CLOCK_REALTIME} for lease-based read optimization. A negative
 * monotonic offset causes a follower to believe it has not heard from the leader for longer than
 * the actual elapsed time, triggering a spurious leader election. A positive offset on the leader
 * causes it to believe its heartbeat is ahead of schedule and may delay sending the next one.
 *
 * <p>Distributed locks backed by Redis (SET with EX) use wall-clock time for TTL computation.
 * If the client clock is rewound by even 1 second, a lock acquired with a 2-second TTL may
 * already appear expired before the application has had a chance to use it. The
 * {@code defaultValue} of {@code -60_000L} (−60 s) exercises this class of bugs in an obvious way.
 *
 * <p>Java's {@code System.currentTimeMillis()} reads {@code CLOCK_REALTIME}; {@code System.nanoTime()}
 * reads {@code CLOCK_MONOTONIC}. Both are affected by this annotation because the interposer
 * applies to all {@code clock_gettime} variants regardless of which clock id is passed.
 *
 * <p>Sibling annotation: {@link ChaosClockGettimeLatency} increases the cost of reading the clock
 * without changing the value; useful for testing timeout over-runs rather than clock drift.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeOffset(deltaMs = -5_000L, probability = 1.0)
 * class ClockSkewTest {
 *   @Test
 *   void distributedLockRemainsValidUnderNegativeClockSkew(ConnectionInfo info) {
 *     // assert that the lock holder detects the skew and does not release the lock early
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeLatency
 * @see ChaosWildcardLatency
 */
@Repeatable(ChaosClockGettimeOffset.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeOffsetTranslator")
public @interface ChaosClockGettimeOffset {

  /**
   * @return clock shift in milliseconds; negative = rewind, positive = advance
   */
  long deltaMs() default -60_000L;

  /**
   * @return probability the offset fires when matched, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-time
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosClockGettimeOffset(id = "primary",  probability = 0.001)
   * @ChaosClockGettimeOffset(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosClockGettimeOffset[] value();
  }
}
