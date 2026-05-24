/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.clock_gettime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.time.annotation.l1.TimeLatencyBinding;
import com.macstab.chaos.time.model.TimeSelector;

/**
 * Delays every {@code clock_gettime(2)} call by {@link #delayMs} milliseconds before delegating to
 * the real kernel call, making the call succeed but consume measurably more wall-clock time.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code CLOCK_GETTIME}, effect =
 * LATENCY) tuple. Unlike errno variants, the latency primitive always invokes the real kernel call
 * after the configured delay — the timespec is correctly populated and the return value is 0. No
 * runtime selector-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code clock_gettime} call the interposer sleeps for {@link #delayMs}
 *       milliseconds.
 *   <li>After the delay, the real kernel call is issued; the result is returned to the application
 *       unchanged — no error is injected.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every call to {@code System.currentTimeMillis()}, {@code System.nanoTime()}, and any JNI
 *       bridge that reads the monotonic or wall clock will block for the configured delay.
 *   <li>Application-level timeouts computed from consecutive clock readings will appear larger than
 *       they truly are; lease durations and heartbeat intervals may expire prematurely.
 *   <li>Distributed locking libraries (Redisson, Jedis) and Raft leaders that use monotonic time
 *       for lease expiry may abdicate leadership when the delay exceeds the heartbeat interval.
 *   <li>Assert that the application's timeout handling is correctly bounded and does not cascade
 *       into a thundering-herd reconnect storm.
 * </ul>
 *
 * <p>In production, elevated latency in {@code clock_gettime} is caused by vDSO unavailability
 * (e.g. inside a VM with broken TSC calibration, forcing a real syscall), kernel scheduling jitter
 * under memory pressure, or CPU frequency scaling that slows the TSC counter.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Under normal conditions {@code clock_gettime(CLOCK_MONOTONIC)} is served by the vDSO in
 * sub-microsecond time; no kernel boundary is crossed. When the vDSO is disabled or miscalibrated
 * the kernel boundary must be crossed and the typical latency rises to 1–5 µs. Under extreme
 * scheduler contention (a fully saturated CFS runqueue) the latency can reach hundreds of
 * microseconds.
 *
 * <p>{@code libchaos-time.so} injects the delay at the C library wrapper level, uniformly affecting
 * both the vDSO fast path and the real-syscall fallback. Values of 10–200 ms correspond to the
 * wall-clock cost of a context switch storm or a page fault cascade; values above 1 s simulate a
 * frozen clock (e.g. a VM pause or a live-migration hiccup).
 *
 * <p>Frameworks most sensitive to {@code clock_gettime} latency: Caffeine's window-TinyLFU expiry
 * (samples the clock on every cache access), Micrometer's {@code Timer} (records wall-clock on
 * every observation), Netty's {@code HashedWheelTimer} (advances the wheel on every tick), Redis
 * client socket timeout computation, and JVM GC safepoint bias calculations.
 *
 * <p>Sibling annotation: {@link ChaosClockGettimeOffset} skews the returned timestamp by a fixed
 * delta instead of slowing the call; useful for simulating clock drift between cluster nodes.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeLatency(delayMs = 150)
 * class ClockGettimeLatencyTest {
 *   @Test
 *   void distributedLockDoesNotExpireOnClockJitter(ConnectionInfo info) {
 *     // assert that the lock is not prematurely released when clock reads are slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeOffset
 * @see ChaosNanosleepLatency
 * @see com.macstab.chaos.time.annotation.l1.TimeLatencyBinding
 */
@Repeatable(ChaosClockGettimeLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeLatencyTranslator")
@TimeLatencyBinding(selector = TimeSelector.CLOCK_GETTIME)
public @interface ChaosClockGettimeLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 10L;

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
   * @ChaosClockGettimeLatency(id = "primary",  probability = 0.001)
   * @ChaosClockGettimeLatency(id = "replica",  probability = 0.01)
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
    ChaosClockGettimeLatency[] value();
  }
}
