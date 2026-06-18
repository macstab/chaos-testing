/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.poll;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding;
import com.macstab.chaos.connection.annotation.l1.connect.ChaosConnectLatency;
import com.macstab.chaos.connection.annotation.l1.recv.ChaosRecvLatency;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Delays every {@code poll(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making readiness detection slower than the application
 * expects while still reporting the correct socket readiness state.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code POLL}, effect = LATENCY) tuple.
 * Unlike errno variants, the latency primitive always delegates to the real kernel call after the
 * configured extra delay — the poll result is authentic (it reflects actual socket readiness). A
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
 *   <li>On each intercepted {@code poll} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer sleeps for {@link #delayMs} ms before issuing
 *       the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Event loops that use {@code poll} to wait for socket readiness will see increased latency
 *       between a socket becoming ready and the application processing the event; assert that
 *       end-to-end request latency increases proportionally to the injected delay.
 *   <li>Blocking I/O implementations that use {@code poll} to implement {@code SO_TIMEOUT} will
 *       consume part of their timeout budget in the injected delay before the kernel even starts
 *       waiting for the socket; assert that timeouts are configured with enough headroom.
 *   <li>Multiplexing servers that use {@code poll} to monitor many file descriptors simultaneously
 *       will see the delay applied to every polling cycle, multiplying the effective delay impact
 *       under high-connection-count scenarios.
 *   <li>Assert that the application's request timeout begins from when the request is dispatched,
 *       not from when poll returns, so that poll latency does not silently consume request budget.
 * </ul>
 *
 * <p>In production, slow {@code poll} calls occur when the process has many open file descriptors
 * and the kernel must scan the entire pollfd array (an O(n) operation), when the process is CPU
 * throttled by cgroups and spends extended periods waiting to be scheduled back after the poll
 * syscall, and during NIC driver overload when interrupt coalescing introduces additional delay
 * before the kernel processes the readiness event.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code poll(2)} and its variants ({@code ppoll}, {@code epoll_wait}) are the mechanism by
 * which event-driven servers detect when sockets are ready for reading or writing without blocking
 * on the socket itself. The delay is injected before the kernel call, so the kernel begins waiting
 * for readiness only after the injected sleep. This means that if a socket is already ready when
 * {@code poll} is called, the poll result is still delayed by {@link #delayMs} — simulating a
 * scheduling stall where the kernel's readiness notification was queued but the application thread
 * was not scheduled to consume it.
 *
 * <p>Netty, Vert.x, and other NIO-based frameworks use {@code epoll_wait} rather than {@code poll}
 * on Linux; this injection targets the POSIX {@code poll} wrapper, which may be called by non-NIO
 * code paths within the container (e.g., Redis's blocking command implementation). For event-driven
 * Java servers, {@link ChaosRecvLatency} and {@link ChaosConnectLatency} are more relevant; {@code
 * ChaosPollLatency} is most useful for testing C/C++ or Python servers that use blocking {@code
 * poll} calls.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosPollLatency(delayMs = 50, toxicity = 0.5)
 * class PollLatencyTest {
 *   @Test
 *   void serverRequestLatencyIncreasesUnderSlowPoll(ConnectionInfo info) {
 *     // assert that end-to-end latency increases by approximately delayMs per poll cycle
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosPollTimeout
 * @see ChaosRecvLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding
 */
@Repeatable(ChaosPollLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.POLL)
public @interface ChaosPollLatency {

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
   * @ChaosPollLatency(id = "primary",  probability = 0.001)
   * @ChaosPollLatency(id = "replica",  probability = 0.01)
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
    ChaosPollLatency[] value();
  }
}
