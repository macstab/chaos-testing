/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.recv;

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
 * Delays every {@code recv(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making data reception slower than the application expects
 * while still delivering the actual received data.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RECV}, effect = LATENCY) tuple.
 * Unlike errno variants, the latency primitive always delegates to the real kernel call after the
 * configured extra delay — the received data is authentic. A Bernoulli trial with probability
 * {@link #toxicity} gates whether the delay fires on each call. No runtime operation-effect
 * validation is needed.
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
 *   <li>On each intercepted {@code recv} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer sleeps for {@link #delayMs} ms before issuing
 *       the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>End-to-end request latency increases by the injected delay on each recv call; assert that
 *       the application's read timeout is configured to accommodate the injected delay without
 *       triggering a false timeout.
 *   <li>Streaming protocols that perform many small reads (e.g., reading a byte at a time for
 *       delimiter detection) accumulate the injected delay on every read call; assert that the
 *       protocol implementation uses buffered reading to minimize the number of recv calls.
 *   <li>The delay is injected before the kernel call, so if the socket has no data available, the
 *       thread sleeps for {@link #delayMs} before entering the kernel's blocking wait; the actual
 *       data arrival is not affected by the injection.
 *   <li>Assert that the server's read timeout begins from the time the recv call is issued, not
 *       from when data arrives at the kernel; this ensures that slow recv consumers are detected by
 *       the timeout even when data is available in the kernel buffer.
 * </ul>
 *
 * <p>In production, slow {@code recv} calls occur when the process is CPU throttled by cgroups and
 * spends extended time waiting to be scheduled after a system call, when NUMA topology causes the
 * process to run on a CPU distant from the NIC's memory domain, and when the kernel's socket buffer
 * is fragmented and the memcpy from kernel to user space is slower than normal.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code recv} syscall typically returns in time proportional to the network round-trip time
 * plus the server's processing time. The injected delay is added before the syscall, so the total
 * receive time observed by the caller is {@link #delayMs} plus the time for data to arrive in the
 * kernel's receive buffer. If data is already buffered when {@code recv} is called (common in
 * pipelined protocols), the total time is approximately {@link #delayMs} — dominated by the
 * injection.
 *
 * <p>This injection is particularly effective for testing request timeout configurations in
 * pipelined or streaming protocols where multiple recv calls are needed per request: a delay on
 * each recv call accumulates across the full request pipeline. For a protocol that requires N recv
 * calls to complete one request, the effective per-request latency increase is N × {@link
 * #delayMs}. This helps reveal whether timeout configurations are calibrated for the worst-case
 * number of recv calls per request.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosRecvLatency(delayMs = 100, toxicity = 0.3)
 * class RecvLatencyTest {
 *   @Test
 *   void readTimeoutFiresCorrectlyUnderSlowRecv(ConnectionInfo info) {
 *     // assert that read timeout fires when recv delay exceeds the configured threshold
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRecvEtimedout
 * @see ChaosRecvEconnreset
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding
 */
@Repeatable(ChaosRecvLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.RECV)
public @interface ChaosRecvLatency {

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
   * @ChaosRecvLatency(id = "primary",  probability = 0.001)
   * @ChaosRecvLatency(id = "replica",  probability = 0.01)
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
    ChaosRecvLatency[] value();
  }
}
