/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.send;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Injects {@code ETIMEDOUT} into {@code send(2)}, causing the call to return {@code -1} with {@code
 * errno = ETIMEDOUT} as if the TCP retransmission timer exhausted its retry limit after the kernel
 * sent data that was never acknowledged by the remote peer, terminating the connection.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SEND}, errno = {@code
 * ETIMEDOUT}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code send} call; when it fires the interposer returns {@code -1} with {@code errno
 * = ETIMEDOUT} without performing any real kernel operation. No runtime operation-errno validation
 * is needed.
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
 *   <li>On each intercepted {@code send} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets {@code errno =
 *       ETIMEDOUT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ETIMEDOUT} from {@code send} means the connection is permanently terminated by the
 *       kernel; data that was in the send buffer was never acknowledged and is lost. The socket
 *       must be closed and replaced. Assert that the application closes the socket on receipt of
 *       {@code ETIMEDOUT} and does not attempt further operations on it.
 *   <li>Data loss is a defining characteristic of send-time timeout: unlike {@code ECONNRESET}
 *       (where the remote peer acknowledges the RST and data delivery can sometimes be determined),
 *       {@code ETIMEDOUT} from send indicates that the peer was unreachable and no data was
 *       confirmed received. Assert that non-idempotent operations are not retried without
 *       application-level deduplication.
 *   <li>Connection pools must evict the timed-out connection and create a replacement; assert that
 *       subsequent requests are served from a freshly established connection.
 *   <li>Assert that the application emits a send-timeout metric or alert with the remote address,
 *       enabling operators to correlate the event with network partition logs.
 * </ul>
 *
 * <p>In production, {@code ETIMEDOUT} from {@code send} occurs when a network partition forms
 * between sender and receiver after the TCP connection is established: the kernel continues to
 * retransmit unacknowledged segments according to exponential backoff until the retransmission
 * limit (controlled by {@code tcp_retries2}, default 15, approximately 15 minutes) is exhausted, at
 * which point the kernel closes the connection and sets the socket error to {@code ETIMEDOUT}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>When data is sent on a TCP socket, the kernel places it in the retransmission queue and starts
 * the retransmission timer. If an ACK is not received before the timer fires, the kernel
 * retransmits the segment and doubles the timer interval (exponential backoff). This continues
 * until {@code tcp_retries2} retransmissions have been attempted without acknowledgement, at which
 * point the kernel marks the connection as dead and returns {@code ETIMEDOUT} on the next {@code
 * send} call (or on a pending blocking {@code send}, unblocking the thread). The total
 * retransmission timeout depends on the current RTT and the number of retries; with default
 * settings it is approximately 15 minutes.
 *
 * <p>This injection delivers {@code ETIMEDOUT} immediately without waiting for the 15-minute
 * retransmission sequence, making it practical to test the application's dead-connection handling
 * without the real multi-minute wait. This is especially valuable in tests that verify retry
 * budgets, circuit-breaker thresholds, and connection-pool eviction — all of which need the timeout
 * to happen on a testable time scale.
 *
 * <p>The distinction between send-time and recv-time {@code ETIMEDOUT} matters for recovery: on
 * send-time timeout, the data was not delivered (unconfirmed); on recv-time timeout, the data may
 * or may not have been processed by the server before the connection was lost. Both require the
 * socket to be closed, but the idempotency constraints for retry differ.
 *
 * <p>Java maps {@code ETIMEDOUT} from {@code send} to a {@code SocketException} with the message
 * "Connection timed out". This is the same message used for {@code ETIMEDOUT} from {@code connect}
 * and {@code recv}; application code that inspects the message text cannot distinguish which phase
 * (connecting, sending, or receiving) caused the timeout without inspecting the call stack or
 * wrapping the socket operation in a typed exception.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSendEtimedout(toxicity = 0.02)
 * class SendEtimedoutTest {
 *   @Test
 *   void connectionPoolEvictsAndReplacesTimedOutConnection(ConnectionInfo info) {
 *     // assert pool closes the timed-out socket and reconnects for subsequent requests
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSendEconnreset
 * @see ChaosSendEpipe
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSendEtimedout.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SEND, errno = Errno.ETIMEDOUT)
public @interface ChaosSendEtimedout {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
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
   * @ChaosSendEtimedout(id = "primary",  probability = 0.001)
   * @ChaosSendEtimedout(id = "replica",  probability = 0.01)
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
    ChaosSendEtimedout[] value();
  }
}
