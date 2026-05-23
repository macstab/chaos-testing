/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.shutdown;

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
 * Injects {@code ENOTCONN} into {@code shutdown(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOTCONN} as if the socket is not in a connected state and the kernel cannot
 * shut down a connection that was never fully established.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SHUTDOWN}, errno = {@code ENOTCONN})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code shutdown} call; when it fires the interposer returns {@code -1} with {@code errno = ENOTCONN}
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
 *   <li>On each intercepted {@code shutdown} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = ENOTCONN}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENOTCONN} from {@code shutdown} indicates that the socket was never connected or
 *       that the connection was already lost before the shutdown call was made; this is a benign
 *       condition in cleanup code. Assert that the application treats {@code ENOTCONN} from
 *       {@code shutdown} as a no-op and proceeds to close the socket without logging an error.
 *   <li>Connection pool teardown code that calls {@code shutdown} before evicting a connection
 *       must tolerate {@code ENOTCONN}; assert that the pool proceeds to close the socket and
 *       does not leave the file descriptor open when {@code shutdown} fails.
 *   <li>Graceful shutdown sequences that rely on {@code shutdown(SHUT_WR)} to signal the end of
 *       the write stream must handle {@code ENOTCONN} without assuming the remote peer received a
 *       FIN; assert that the application falls back to abrupt close or connection replacement.
 *   <li>Assert that {@code ENOTCONN} from {@code shutdown} does not propagate to application-layer
 *       callers as a connection error — the underlying connection was already gone, and the shutdown
 *       failure is a symptom, not a new fault.
 * </ul>
 *
 * <p>In production, {@code ENOTCONN} from {@code shutdown} occurs when a connection is terminated
 * by the remote peer (RST or FIN received) concurrently with the local application calling
 * {@code shutdown}: the kernel has already torn down the connection's transport state when the
 * {@code shutdown} syscall arrives, causing it to return {@code ENOTCONN}. It is also returned
 * when {@code shutdown} is called on a socket that was never connected, which can happen in
 * accept-loop error paths that attempt to shut down a socket from which {@code accept} returned
 * an error.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel returns {@code ENOTCONN} from {@code shutdown} when the socket's internal state
 * machine is not in the ESTABLISHED, CLOSE_WAIT, or FIN_WAIT_1/2 state — that is, when the TCP
 * connection is not currently in a state that has an active write-half. This can happen if the
 * remote peer's RST has already caused the kernel to transition the socket to the CLOSE state
 * before the application's {@code shutdown} call arrives, creating a race window that is common
 * in high-concurrency servers.
 *
 * <p>For UDP sockets, {@code shutdown} is always valid (UDP is connectionless), so {@code ENOTCONN}
 * is typically only encountered on TCP, SCTP, or other connection-oriented socket types. On Linux,
 * calling {@code shutdown} on an unconnected TCP socket (in the LISTEN or SYN_SENT state) also
 * returns {@code ENOTCONN} for the write direction, since there is no established write-half to
 * shut down.
 *
 * <p>Java's {@code Socket.shutdownInput()} and {@code Socket.shutdownOutput()} methods throw
 * {@code SocketException} with the message "Socket is not connected" when the underlying
 * {@code shutdown} syscall returns {@code ENOTCONN}. Java's {@code Socket.close()} method
 * suppresses the error entirely because the JVM's close path is designed to be idempotent
 * and tolerant of already-closed or not-connected sockets.
 *
 * <p>The primary value of injecting {@code ENOTCONN} on shutdown is to test that cleanup and
 * teardown code paths — connection pool eviction, request cancellation, and server-side connection
 * management — handle the case where the connection was already gone when the cleanup runs.
 * These paths are rarely exercised in unit tests because the error requires a race between two
 * concurrent operations.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosShutdownEnotconn(toxicity = 0.1)
 * class ShutdownEnotconnTest {
 *   @Test
 *   void connectionCloseCompletesWhenShutdownFindsSocketNotConnected(ConnectionInfo info) {
 *     // assert that close() succeeds even when shutdown() returns ENOTCONN
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosShutdownEinval
 * @see ChaosShutdownLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosShutdownEnotconn.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SHUTDOWN, errno = Errno.ENOTCONN)
public @interface ChaosShutdownEnotconn {

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
   * @ChaosShutdownEnotconn(id = "primary",  probability = 0.001)
   * @ChaosShutdownEnotconn(id = "replica",  probability = 0.01)
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
    ChaosShutdownEnotconn[] value();
  }
}
