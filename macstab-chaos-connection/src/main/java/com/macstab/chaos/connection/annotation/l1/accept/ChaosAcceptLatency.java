/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.accept;

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
 * Delays every {@code accept(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making connection acceptance slower than the application
 * expects while still producing a valid accepted connection fd.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ACCEPT}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the return value is a valid accepted connection fd. A
 * Bernoulli trial with probability {@link #toxicity} gates whether the delay fires on each call. No
 * runtime operation-effect validation is needed.
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
 *   <li>On each intercepted {@code accept} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer sleeps for {@link #delayMs} ms before
 *       issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Each accepted connection takes longer to return from the accept call; during the delay the
 *       client's connection is in the kernel's accept queue but not yet returned to the
 *       application.
 *   <li>In single-threaded accept loops, the delay reduces throughput by blocking the thread that
 *       would normally call accept again immediately; assert that throughput decreases
 *       proportionally.
 *   <li>In multi-threaded servers with a dedicated accept thread, the delay backs up the accept
 *       queue; when the queue fills, the kernel rejects new SYN packets, causing client-side
 *       connection timeouts.
 *   <li>Assert that the server's accept-queue depth metric increases during the injection and that
 *       the server correctly reports degraded throughput rather than failing silently.
 * </ul>
 *
 * <p>In production, slow {@code accept} calls occur when the kernel's TCP accept queue management
 * is under memory pressure, when the accept queue draining thread is starved of CPU by
 * higher-priority threads, or when the server is running under cgroup CPU throttling.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code accept(2)} normally returns very quickly because the kernel pre-completes the TCP
 * three-way handshake and queues the completed connection before the application calls {@code
 * accept}. The syscall itself is typically sub-millisecond. Adding {@link #delayMs} before the
 * syscall simulates the accept thread being descheduled before it can drain the queue — a condition
 * that occurs under high CPU contention.
 *
 * <p>Event-driven servers (Netty, Vert.x) process many connections per accept-loop iteration by
 * calling {@code accept} in a tight loop until {@code EAGAIN}. Delaying each accept call reduces
 * the rate at which the server drains the accept queue, causing clients to wait in the queue for
 * longer. The Netty accept loop detects this as increased accept-latency and can emit a warning
 * metric.
 *
 * <p>The delay is injected before the kernel call, so the accepted connection fd is still valid
 * after the delay and no resource leak occurs. This distinguishes the latency injection from error
 * injections, which do not produce a valid fd and require no cleanup from the application.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosAcceptLatency(delayMs = 100, toxicity = 0.5)
 * class AcceptLatencyTest {
 *   @Test
 *   void serverThroughputDegradesGracefullyUnderAcceptDelay(ConnectionInfo info) {
 *     // assert that accept-queue depth metric increases and throughput decreases proportionally
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAcceptEagain
 * @see ChaosConnectLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding
 */
@Repeatable(ChaosAcceptLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.ACCEPT)
public @interface ChaosAcceptLatency {

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
   * @ChaosAcceptLatency(id = "primary",  probability = 0.001)
   * @ChaosAcceptLatency(id = "replica",  probability = 0.01)
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
    ChaosAcceptLatency[] value();
  }
}
