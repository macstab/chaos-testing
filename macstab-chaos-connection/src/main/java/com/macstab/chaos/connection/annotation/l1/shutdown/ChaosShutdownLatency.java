/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.shutdown;

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
 * Delays every {@code shutdown(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making connection teardown slower than the application
 * expects while still completing the graceful close of the write or read direction.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SHUTDOWN}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the shutdown direction is closed normally. A Bernoulli trial
 * with probability {@link #toxicity} gates whether the delay fires on each call. No runtime
 * operation-effect validation is needed.
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
 *   <li>On each intercepted {@code shutdown} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer sleeps for {@link #delayMs} ms before
 *       issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Graceful shutdown sequences that call {@code shutdown(SHUT_WR)} to signal end-of-stream
 *       before reading the final response take longer to complete; assert that the application's
 *       total shutdown timeout accounts for the additional time spent waiting for the {@code
 *       shutdown} call to return.
 *   <li>Connection pool teardown that calls {@code shutdown} on each evicted connection serialises
 *       on this delay if connections are closed sequentially; assert that the pool's shutdown
 *       completes within the configured teardown timeout even when each connection takes {@link
 *       #delayMs} longer to close.
 *   <li>Server-side handlers that call {@code shutdown(SHUT_WR)} to signal the end of the response
 *       before calling {@code close} delay the FIN delivery to the client; assert that the client's
 *       read loop correctly waits for the FIN even when it arrives later than expected.
 *   <li>Assert that the application does not time out the shutdown operation itself — applications
 *       typically do not set a timeout on {@code shutdown} calls, but teardown path deadlines can
 *       be exceeded if shutdown is unexpectedly slow.
 * </ul>
 *
 * <p>In production, slow {@code shutdown} calls occur when the kernel's TCP state machine must wait
 * for in-flight ACKs before transitioning to the half-closed state, when the process is CPU
 * throttled and waits to be scheduled before entering the kernel, and when connection teardown
 * coincides with high kernel memory pressure that slows socket buffer deallocation.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code shutdown(2)} syscall is normally fast: for {@code SHUT_WR} it sends a TCP FIN
 * (triggering the FIN_WAIT_1 transition) and for {@code SHUT_RD} it marks the receive half as
 * closed. Neither operation waits for network round-trips; the call returns immediately after
 * updating the socket state and queuing the FIN segment. The injected delay is therefore purely
 * artificial — it simulates process scheduling stalls that delay the shutdown call from being
 * issued to the kernel, rather than any real kernel-side latency in the shutdown operation.
 *
 * <p>The primary use case for this injection is to test graceful shutdown sequences where one side
 * calls {@code shutdown(SHUT_WR)} and then waits for the remote peer to drain the connection and
 * respond with its own FIN. If the initial shutdown is delayed, the remote peer's draining period
 * starts later, and the total graceful shutdown time increases by the injected delay. This is
 * particularly relevant for HTTP/1.1 servers that use half-close to signal end-of-response (sending
 * FIN after the last response byte) and then wait for the client's acknowledgment.
 *
 * <p>Java's {@code Socket.shutdownOutput()} translates directly to {@code shutdown(SHUT_WR)};
 * {@code Socket.shutdownInput()} translates to {@code shutdown(SHUT_RD)}; and {@code
 * Socket.close()} internally calls {@code shutdown(SHUT_RDWR)} before closing the file descriptor.
 * All three paths are intercepted by libchaos-net, so any of them will be delayed when this
 * annotation is applied.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosShutdownLatency(delayMs = 100, toxicity = 0.2)
 * class ShutdownLatencyTest {
 *   @Test
 *   void connectionPoolTeardownCompletesWithinDeadlineUnderSlowShutdown(ConnectionInfo info) {
 *     // assert that pool teardown finishes within its configured shutdown timeout
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosShutdownEinval
 * @see ChaosShutdownEnotconn
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding
 */
@Repeatable(ChaosShutdownLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.SHUTDOWN)
public @interface ChaosShutdownLatency {

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
   * @ChaosShutdownLatency(id = "primary",  probability = 0.001)
   * @ChaosShutdownLatency(id = "replica",  probability = 0.01)
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
    ChaosShutdownLatency[] value();
  }
}
