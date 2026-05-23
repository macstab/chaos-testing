/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.wildcard;

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
 * Extends every interposed time syscall ({@code clock_gettime}, {@code nanosleep}, {@code usleep})
 * by an additional {@link #delayMs} milliseconds before delegating to the real kernel call, making
 * all time operations slower than the application expects.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code WILDCARD}, effect = LATENCY)
 * tuple. The {@code WILDCARD} selector matches all three interposed time syscalls simultaneously —
 * equivalent to applying {@link ChaosClockGettimeLatency}, {@link ChaosNanosleepLatency}, and
 * {@link ChaosUsleepLatency} in a single annotation. Unlike errno variants, the latency primitive
 * always delegates to the real kernel call after the configured extra delay — the return value
 * is 0 (success). No runtime selector-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted call to any of the three syscalls the interposer first sleeps for an
 *       additional {@link #delayMs} milliseconds.
 *   <li>After the extra delay, the real kernel call is issued with the original arguments; the
 *       result is returned to the application unchanged — no error is injected.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Timestamp reads ({@code clock_gettime}) are delayed, causing elapsed-time calculations to
 *       overestimate wall-clock durations and potentially trigger spurious timeout events in
 *       deadline-sensitive code paths.
 *   <li>Sleep calls ({@code nanosleep}, {@code usleep}) take longer than requested; pacing loops,
 *       rate limiters, and connection keep-alive heartbeats miss their configured intervals.
 *   <li>Profiling and APM agents that sample {@code clock_gettime} at high frequency will appear
 *       to record higher latency than actual, corrupting performance metrics.
 *   <li>Assert that SLA budgets account for time-syscall overhead and that keep-alive intervals
 *       include sufficient headroom above the injected extra delay.
 * </ul>
 *
 * <p>In production, extended time-syscall durations are caused by kernel scheduling stalls, cgroup
 * CPU quota throttling, vDSO cache pressure, and hypervisor CPU steal on noisy-neighbour cloud hosts.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code WILDCARD} latency injection is the most comprehensive time-chaos primitive: it adds
 * overhead simultaneously to clock reads, nanosecond sleeps, and microsecond sleeps. This exercises
 * all three categories of time-dependent logic — timekeeping, backoff, and polling — with a single
 * annotation. Applications with distributed coordination logic (e.g. lease TTLs, Raft heartbeats)
 * are particularly sensitive: a sufficiently large {@link #delayMs} can cause lease expiry before
 * renewal, triggering leader election races under artificial conditions.
 *
 * <p>On Linux, {@code clock_gettime(CLOCK_MONOTONIC)} is normally served from the vDSO without a
 * kernel context switch. The extra delay injected by {@code libchaos-time.so} occurs in userspace
 * inside the PLT interposer, so it is still charged to the calling thread and visible to profilers.
 * The net effect is that {@code clock_gettime} appears to take {@link #delayMs} ms longer than
 * normal — a condition that mimics extreme vDSO cache thrashing or hypervisor clock-source
 * virtualization overhead.
 *
 * <p>C libraries embedded via JNI (librdkafka, libpq, libcurl) rely on all three time interfaces
 * for retry timers, poll intervals, and connection keep-alive. Applying {@code WILDCARD} latency
 * exercises the interaction between these three interfaces simultaneously, which can reveal
 * assumptions such as "if {@code nanosleep} is slow, the retry budget must already be consumed" —
 * assumptions that break when independent latency is applied to each.
 *
 * <p>Sibling per-syscall annotations ({@link ChaosClockGettimeLatency}, {@link ChaosNanosleepLatency},
 * {@link ChaosUsleepLatency}) allow targeted latency injection to a single time interface when a
 * narrower test scope is needed. Use the wildcard form for full system-wide time-overhead testing.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosWildcardLatency(delayMs = 200)
 * class WildcardLatencyTest {
 *   @Test
 *   void applicationRemainsStableWhenAllTimeSyscallsAreSlowed(ConnectionInfo info) {
 *     // assert that keep-alive intervals are not missed and SLA budgets hold
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeLatency
 * @see ChaosNanosleepLatency
 * @see ChaosUsleepLatency
 * @see com.macstab.chaos.time.annotation.l1.TimeLatencyBinding
 */
@Repeatable(ChaosWildcardLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeLatencyTranslator")
@TimeLatencyBinding(selector = TimeSelector.WILDCARD)
public @interface ChaosWildcardLatency {

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
   * @ChaosWildcardLatency(id = "primary",  probability = 0.001)
   * @ChaosWildcardLatency(id = "replica",  probability = 0.01)
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
    ChaosWildcardLatency[] value();
  }
}
