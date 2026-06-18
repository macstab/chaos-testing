/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.usleep;

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
 * Extends every {@code usleep(3)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making the sleep longer than the application requested.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code USLEEP}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after sleeping the configured extra duration — the return value is 0 (success). No runtime
 * selector-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code usleep} call the interposer first sleeps for an additional
 *       {@link #delayMs} milliseconds.
 *   <li>After the extra delay, the real kernel call is issued with the original {@code usec}
 *       argument; the result is returned to the application unchanged — no error is injected.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Rate-limiting loops that use {@code usleep} for pacing will run more slowly than
 *       configured, reducing throughput below the target rate.
 *   <li>Polling loops in client libraries (database connectors, message broker clients) that sleep
 *       between polls will miss their desired poll interval, increasing latency.
 *   <li>Connection keep-alive loops that sleep between heartbeats will miss intervals, potentially
 *       triggering server-side idle timeout disconnects.
 *   <li>Assert that SLA budgets account for scheduler jitter and that keep-alive intervals include
 *       sufficient headroom above the extra delay.
 * </ul>
 *
 * <p>In production, extended {@code usleep} durations are caused by kernel scheduling stalls,
 * cgroup CPU quota throttling, and hypervisor CPU steal on noisy-neighbour cloud hosts.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code usleep(3)} is a microsecond-precision sleep wrapper over {@code nanosleep(2)}; its
 * nominal precision is limited by kernel timer resolution (typically 1 ms on HZ=1000 kernels). The
 * extra latency injected by {@code libchaos-time.so} simulates the effect of the OS not returning
 * to the process promptly after the timer fires — a common occurrence when the CFS runqueue is
 * saturated or the CPU is being stolen by another VM.
 *
 * <p>C libraries embedded in JNI (libcurl, librdkafka, libpq) frequently use {@code usleep} in
 * their connection retry and poll loops. Extending those sleeps forces the application to wait
 * longer than the library's configured retry interval, which can cascade into connection pool
 * exhaustion or request timeout failures at the application layer.
 *
 * <p>Sibling annotation: {@link
 * com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepLatency} applies the same delay to
 * the modern {@code nanosleep} interface; {@link
 * com.macstab.chaos.time.annotation.l1.wildcard.ChaosWildcardLatency} applies it to all interposed
 * time syscalls simultaneously.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosUsleepLatency(delayMs = 300)
 * class UsleepLatencyTest {
 *   @Test
 *   void connectionPoolDoesNotExhaustUnderExtendedPollDelay(ConnectionInfo info) {
 *     // assert that the pool survives a 300 ms extra delay in the poll cycle
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepLatency
 * @see com.macstab.chaos.time.annotation.l1.wildcard.ChaosWildcardLatency
 * @see com.macstab.chaos.time.annotation.l1.TimeLatencyBinding
 */
@Repeatable(ChaosUsleepLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeLatencyTranslator")
@TimeLatencyBinding(selector = TimeSelector.USLEEP)
public @interface ChaosUsleepLatency {

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
   * @ChaosUsleepLatency(id = "primary",  probability = 0.001)
   * @ChaosUsleepLatency(id = "replica",  probability = 0.01)
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
    ChaosUsleepLatency[] value();
  }
}
