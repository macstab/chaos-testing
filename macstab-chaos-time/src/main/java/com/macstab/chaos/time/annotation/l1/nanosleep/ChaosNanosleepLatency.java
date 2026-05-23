/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.nanosleep;

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
 * Extends every {@code nanosleep(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making the sleep longer than the application requested.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code NANOSLEEP}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after sleeping the configured extra duration — the return value is 0 (success). No runtime
 * selector-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code nanosleep} call the interposer first sleeps for an additional
 *       {@link #delayMs} milliseconds.
 *   <li>After the extra delay, the real kernel call is issued with the original {@code timespec};
 *       the result is returned to the application unchanged — no error is injected.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Back-pressure loops that use {@code nanosleep} for pacing will run more slowly than
 *       configured, causing throughput to drop below the expected rate limit.
 *   <li>Heartbeat timers that sleep between heartbeats will miss their intervals, potentially
 *       causing timeout-based leader elections or watchdog trips.
 *   <li>Rate-limiters that measure elapsed time between consecutive operations will observe a
 *       larger interval and may over-estimate available capacity.
 *   <li>Assert that SLA budgets account for scheduler jitter of at least the configured extra
 *       delay and that watchdog timeouts are set with sufficient headroom.
 * </ul>
 *
 * <p>In production, extended {@code nanosleep} durations are caused by kernel scheduling stalls
 * (CFS runqueue saturation), CPU throttling due to cgroup quotas, or the hypervisor stealing CPU
 * cycles from the vCPU during a high-priority task on the physical host.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code nanosleep(2)} guarantees that the sleep is at least as long as requested; the kernel
 * may deliver the wake-up later than exactly requested due to timer granularity and scheduling.
 * The extra latency injected by {@code libchaos-time.so} simulates an extreme version of this
 * over-sleep, stretching what is normally a microsecond-level jitter into a configurable
 * millisecond-level stall.
 *
 * <p>This is particularly relevant for Redis client idle-timeout handling, connection pool
 * eviction loops, and Kubernetes liveness probe loops — all of which use sleep-based scheduling
 * and have strict timeout budgets that must accommodate scheduler jitter.
 *
 * <p>Sibling annotation: {@link ChaosNanosleepEintr} cuts the sleep short instead of extending
 * it; combining both annotations exercises both the under-sleep and over-sleep dimensions.
 * {@link ChaosWildcardLatency} applies the same delay to all interposed time syscalls simultaneously.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosNanosleepLatency(delayMs = 500)
 * class NanosleepLatencyTest {
 *   @Test
 *   void heartbeatIntervalAccountsForSchedulerJitter(ConnectionInfo info) {
 *     // assert that the heartbeat is sent even when nanosleep is 500 ms over budget
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosNanosleepEintr
 * @see ChaosWildcardLatency
 * @see com.macstab.chaos.time.annotation.l1.TimeLatencyBinding
 */
@Repeatable(ChaosNanosleepLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeLatencyTranslator")
@TimeLatencyBinding(selector = TimeSelector.NANOSLEEP)
public @interface ChaosNanosleepLatency {

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
   * @ChaosNanosleepLatency(id = "primary",  probability = 0.001)
   * @ChaosNanosleepLatency(id = "replica",  probability = 0.01)
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
    ChaosNanosleepLatency[] value();
  }
}
