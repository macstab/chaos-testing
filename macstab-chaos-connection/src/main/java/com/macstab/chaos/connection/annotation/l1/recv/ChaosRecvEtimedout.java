/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.recv;

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
 * Injects {@code ETIMEDOUT} into {@code recv(2)}, causing the call to return {@code -1} with
 * {@code errno = ETIMEDOUT} as if the TCP keep-alive probing mechanism determined that the remote
 * peer is no longer reachable after the keep-alive retransmission limit was exceeded.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RECV}, errno = {@code ETIMEDOUT})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code recv} call; when it fires the interposer returns {@code -1} with {@code errno = ETIMEDOUT}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code recv} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ETIMEDOUT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ETIMEDOUT} from {@code recv} indicates that the connection was silently lost (no RST
 *       received); the socket is permanently closed by the kernel and must be replaced. Assert that
 *       the application closes the socket and creates a new connection rather than retrying recv.
 *   <li>Connection pools must detect this error and evict the dead connection; assert that the pool
 *       creates a replacement connection and serves subsequent requests from it.
 *   <li>In-flight requests whose responses were not received are permanently lost; assert that the
 *       application handles the loss correctly — retrying idempotent requests and returning errors
 *       for non-idempotent ones.
 *   <li>Assert that the application emits a "connection timed out" metric or alert with the remote
 *       address, enabling operators to correlate the event with network partition logs.
 * </ul>
 *
 * <p>In production, {@code ETIMEDOUT} from {@code recv} occurs when a connection is held open
 * across a network partition: the TCP keep-alive mechanism (if enabled) sends probes and eventually
 * determines that the peer is unreachable. The timeout duration depends on
 * {@code tcp_keepalive_time}, {@code tcp_keepalive_intvl}, and {@code tcp_keepalive_probes};
 * with defaults, the kernel may take two hours or more to detect the dead connection.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel returns {@code ETIMEDOUT} from {@code recv} in two scenarios: when TCP keep-alive
 * probes exhaust their retry limit (controlled by {@code tcp_keepalive_probes}, default 9) and no
 * ACK is received, indicating the remote peer is permanently unreachable; and when the retransmission
 * timer for unacknowledged data exhausts its limit (controlled by {@code tcp_retries2}, default 15,
 * which gives a timeout of approximately 15 minutes under normal RTT). In both cases the kernel
 * closes the connection and marks the socket as error-pending.
 *
 * <p>This injection delivers {@code ETIMEDOUT} immediately without waiting for keep-alive probes or
 * retransmission timeouts, making it practical to test the application's dead-connection handling
 * without the real multi-minute wait. For tests that need to verify that the application correctly
 * configures TCP keep-alive (so that dead connections are detected faster), use the injection with
 * a low toxicity to simulate the occasional keep-alive timeout that would occur in production.
 *
 * <p>Java maps {@code ETIMEDOUT} from {@code recv} to a {@code SocketException} with the message
 * "Connection timed out". The same message is used for {@code ETIMEDOUT} from {@code connect};
 * application code must inspect the phase (connecting vs. receiving) to apply the correct recovery
 * strategy. Connection pool libraries typically detect both forms and replace the connection.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosRecvEtimedout(toxicity = 0.02)
 * class RecvEtimedoutTest {
 *   @Test
 *   void connectionPoolDetectsAndReplacesTimedOutConnection(ConnectionInfo info) {
 *     // assert that pool detects the timeout, evicts the connection, and reconnects
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRecvEconnreset
 * @see ChaosRecvLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosRecvEtimedout.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.RECV, errno = Errno.ETIMEDOUT)
public @interface ChaosRecvEtimedout {

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
   * @ChaosRecvEtimedout(id = "primary",  probability = 0.001)
   * @ChaosRecvEtimedout(id = "replica",  probability = 0.01)
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
    ChaosRecvEtimedout[] value();
  }
}
