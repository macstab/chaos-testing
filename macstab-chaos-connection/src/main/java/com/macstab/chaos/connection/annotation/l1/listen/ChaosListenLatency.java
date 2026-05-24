/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.listen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Delays every {@code listen(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making accept-queue creation slower than the application
 * expects while still completing the listen operation successfully.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code LISTEN}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the accept queue is created and the socket transitions to the
 * listening state normally. A Bernoulli trial with probability {@link #toxicity} gates whether the
 * delay fires on each call. No runtime operation-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.NET)} on the container definition causes the
 *       extension to upload {@code libchaos-net.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code connect}, {@code accept}, {@code socket}, {@code
 *       bind}, {@code listen}, {@code shutdown}, {@code send}, {@code recv}, and {@code poll} at
 *       the dynamic-linker level.
 *   <li>On each intercepted {@code listen} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer sleeps for {@link #delayMs} ms before
 *       issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Server startup takes longer when the transition to listening state is delayed; assert that
 *       readiness probes and health checks have a generous enough {@code initialDelaySeconds} to
 *       accommodate slow listen calls during startup.
 *   <li>Services that bind and listen on many ports simultaneously (HTTP + HTTPS + metrics + admin)
 *       accumulate per-listen delays; assert that the total startup budget covers all listen calls.
 *   <li>The delay fires before the kernel call, so during the delay the socket is in a bound but
 *       not listening state; clients that attempt to connect during the delay will receive {@code
 *       ECONNREFUSED} because no accept queue exists yet. Assert that clients handle the pre-listen
 *       connection refused correctly.
 *   <li>Assert that the startup sequence does not proceed to serving requests until all listen
 *       calls complete, so that the service's health check endpoint is not reachable before the
 *       main listening port is ready.
 * </ul>
 *
 * <p>In production, slow {@code listen} calls occur under severe kernel memory pressure when the
 * allocation of the accept queue backing structures is stalled, and during cgroup memory limit
 * enforcement where kernel allocations for socket structures are throttled.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code listen(2)} is normally one of the fastest socket setup calls — it allocates the accept
 * queue (a pair of queues: the SYN backlog queue for half-open connections, and the accept queue
 * for completed connections) and marks the socket as passive. Under normal conditions it completes
 * in microseconds. The delay injected by this annotation is therefore larger than real-world listen
 * latency in the vast majority of cases; its value is to expose startup timeout assumptions and
 * readiness probe configurations that do not account for any latency in the socket setup phase.
 *
 * <p>The relationship between backlog size and memory allocation is relevant here: a large backlog
 * value passed to {@code listen} causes the kernel to pre-allocate more memory for the accept
 * queue. On memory-constrained systems this allocation can stall. This injection simulates that
 * stall without requiring actual memory pressure.
 *
 * <p>Combined with {@link ChaosBindLatency}, this injection simulates a slow full socket setup
 * sequence: bind assigns the local address (slow), listen creates the accept queue (slow); both
 * must complete before clients can connect. Injecting delay into both operations reveals whether
 * the startup timeout covers the complete socket initialisation sequence.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosListenLatency(delayMs = 300, toxicity = 0.5)
 * class ListenLatencyTest {
 *   @Test
 *   void readinessProbeToleratesSlowListenDuringStartup(ConnectionInfo info) {
 *     // assert that readiness probe does not fire before all ports are in listening state
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosListenEaddrinuse
 * @see ChaosBindLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding
 */
@Repeatable(ChaosListenLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.LISTEN)
public @interface ChaosListenLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 100L;

  /**
   * @return probability the latency fires when matched, in {@code (0.0, 1.0]}
   */
  double toxicity() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-net
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosListenLatency(id = "primary",  probability = 0.001)
   * @ChaosListenLatency(id = "replica",  probability = 0.01)
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
    ChaosListenLatency[] value();
  }
}
