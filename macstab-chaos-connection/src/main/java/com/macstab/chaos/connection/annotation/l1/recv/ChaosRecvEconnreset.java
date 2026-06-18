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
 * Injects {@code ECONNRESET} into {@code recv(2)}, causing the call to return {@code -1} with
 * {@code errno = ECONNRESET} as if the remote peer sent a TCP RST segment while the connection was
 * established and data exchange was in progress.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RECV}, errno = {@code
 * ECONNRESET}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code recv} call; when it fires the interposer returns {@code -1} with {@code errno
 * = ECONNRESET} without performing any real kernel operation. No runtime operation-errno validation
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
 *   <li>On each intercepted {@code recv} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets {@code errno =
 *       ECONNRESET}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The connection is permanently terminated; the socket file descriptor is still valid and
 *       must be closed by the application — failing to close it leaks a file descriptor.
 *   <li>Assert that the application closes the socket after receiving {@code ECONNRESET} from
 *       {@code recv} and either creates a new connection or returns an error to the caller,
 *       depending on the protocol's retry semantics.
 *   <li>Connection pools must evict the connection from the pool on reset and create a replacement;
 *       assert that the pool does not return a reset connection to a caller.
 *   <li>In-flight requests that were sent but whose responses were not yet received are lost on
 *       reset; assert that the application correctly identifies which requests are outstanding and
 *       either retries idempotent ones or returns errors for non-idempotent ones.
 * </ul>
 *
 * <p>In production, {@code ECONNRESET} from {@code recv} occurs when a load balancer terminates an
 * idle connection (sending RST instead of FIN), when a firewall's connection tracking entry expires
 * and subsequent packets trigger a RST, when a server process crashes while the client has an
 * established connection and is waiting for a response, and during network failover events where
 * stateful network devices reset active connections.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>When the kernel receives a TCP RST segment on an established connection, it immediately
 * terminates the connection and marks the socket as error-pending. The next {@code recv} call on
 * that socket returns {@code -1} with {@code errno = ECONNRESET}. Unlike a clean close (FIN
 * sequence), a reset does not allow pending data to be flushed: any data that was in flight or in
 * the send/receive buffers is discarded. The connection cannot be recovered by re-reading; the fd
 * must be closed and a new connection established.
 *
 * <p>Java maps {@code ECONNRESET} from {@code recv} to a {@code SocketException} with the message
 * "Connection reset". This is one of the most common exceptions in Java networking code;
 * application code that catches it generically without distinguishing the phase (in-request vs.
 * between-request) may apply an incorrect recovery strategy. A reset between requests (while the
 * socket is idle in the pool) is safe to silently replace; a reset during an active request
 * requires either retry or error propagation depending on whether the request was idempotent.
 *
 * <p>Lettuce (Redis client) distinguishes between reset on idle connections (replaced
 * transparently) and reset during command execution (propagated as a {@code
 * RedisCommandExecutionException}). HikariCP marks the connection as dead and removes it from the
 * pool. This injection exercises both code paths depending on when during the connection lifecycle
 * the reset fires.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosRecvEconnreset(toxicity = 0.05)
 * class RecvEconnresetTest {
 *   @Test
 *   void connectionPoolEvictsAndReplacesResetConnection(ConnectionInfo info) {
 *     // assert that pool replaces reset connection and subsequent requests succeed
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRecvEtimedout
 * @see ChaosRecvEagain
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosRecvEconnreset.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.RECV, errno = Errno.ECONNRESET)
public @interface ChaosRecvEconnreset {

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
   * @ChaosRecvEconnreset(id = "primary",  probability = 0.001)
   * @ChaosRecvEconnreset(id = "replica",  probability = 0.01)
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
    ChaosRecvEconnreset[] value();
  }
}
