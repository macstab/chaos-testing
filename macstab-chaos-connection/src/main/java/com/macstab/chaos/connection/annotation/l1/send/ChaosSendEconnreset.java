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
 * Injects {@code ECONNRESET} into {@code send(2)}, causing the call to return {@code -1} with
 * {@code errno = ECONNRESET} as if the remote peer sent a TCP RST segment in response to data
 * being sent on the connection, terminating the connection mid-transfer.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SEND}, errno =
 * {@code ECONNRESET}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code send} call; when it fires the interposer returns {@code -1} with
 * {@code errno = ECONNRESET} without performing any real kernel operation. No runtime
 * operation-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.NET)} on the container definition causes the
 *       extension to upload {@code libchaos-net.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code connect}, {@code accept}, {@code socket},
 *       {@code bind}, {@code listen}, {@code shutdown}, {@code send}, {@code recv}, and
 *       {@code poll} at the dynamic-linker level.
 *   <li>On each intercepted {@code send} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ECONNRESET}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The connection is permanently terminated on send-time reset; the application must close the
 *       socket and discard any data that was in the send buffer — the remote peer did not receive it.
 *   <li>Assert that the application correctly handles partially-sent messages: if a multi-part send
 *       sequence is interrupted by a reset mid-way, the remote side may have received some but not
 *       all of the data; the application must close and reconnect rather than continuing on the
 *       same socket.
 *   <li>Connection pools must evict the connection on send-time reset and create a replacement;
 *       assert that the pool does not return a reset connection to a caller after a reset.
 *   <li>Assert that the application's retry logic for non-idempotent operations (e.g., database
 *       writes) does not blindly retry on ECONNRESET from send, since the remote side may have
 *       already processed the request before sending RST.
 * </ul>
 *
 * <p>In production, {@code ECONNRESET} from {@code send} occurs when the remote server sends RST
 * in response to data it cannot process (e.g., after a timeout, after a protocol violation, or
 * during server restart), when a load balancer terminates a connection while data is in transit,
 * and when a firewall rule change causes packets to be rejected with RST after some data has
 * already been forwarded.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel can receive a RST at any time on an established TCP connection. If the RST arrives
 * while the application is blocked in {@code send}, the kernel unblocks the call and returns
 * {@code -1} with {@code errno = ECONNRESET}. If the RST arrives between calls, it is queued and
 * the error is returned on the next socket operation (send or recv). The data that was in the
 * local send buffer when the RST was received is discarded by the kernel without being transmitted.
 *
 * <p>The key question for idempotency: when {@code ECONNRESET} is returned from {@code send}, the
 * application cannot determine how much (if any) of the sent data reached the remote side. For
 * idempotent operations, the safe response is to retry on a new connection. For non-idempotent
 * operations (payment processing, database writes), the application must use application-level
 * deduplication or transaction IDs to avoid double processing.
 *
 * <p>Java maps {@code ECONNRESET} from {@code send} to a {@code SocketException} with the message
 * "Connection reset by peer". Note that "Connection reset" (without "by peer") is the message for
 * reset during {@code recv}; application code that inspects the message text to distinguish the two
 * cases may fail on non-glibc systems where the messages differ.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSendEconnreset(toxicity = 0.05)
 * class SendEconnresetTest {
 *   @Test
 *   void nonIdempotentOperationsAreNotRetriedOnSendTimeReset(ConnectionInfo info) {
 *     // assert that the application does not blindly retry non-idempotent operations
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSendEpipe
 * @see ChaosSendEtimedout
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSendEconnreset.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SEND, errno = Errno.ECONNRESET)
public @interface ChaosSendEconnreset {

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
   * @ChaosSendEconnreset(id = "primary",  probability = 0.001)
   * @ChaosSendEconnreset(id = "replica",  probability = 0.01)
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
    ChaosSendEconnreset[] value();
  }
}
