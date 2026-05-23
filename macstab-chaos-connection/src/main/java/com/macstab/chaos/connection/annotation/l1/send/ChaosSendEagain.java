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
 * Injects {@code EAGAIN} into {@code send(2)}, causing the call to return {@code -1} with
 * {@code errno = EAGAIN} as if the socket's send buffer is full and the kernel cannot accept any
 * more data for transmission without blocking.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SEND}, errno = {@code EAGAIN})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code send} call; when it fires the interposer returns {@code -1} with {@code errno = EAGAIN}
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
 *   <li>On each intercepted {@code send} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EAGAIN}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Non-blocking senders must re-register for write readiness and retry when they receive
 *       {@code EAGAIN} from {@code send}; assert that the event loop re-arms the writable
 *       notification and continues sending once the buffer drains.
 *   <li>Blocking senders on non-blocking sockets use {@code poll}/{@code select} before sending;
 *       the injection tests that the poll-then-send loop handles the case where poll indicates
 *       writable but send returns {@code EAGAIN} (a race condition that is valid per POSIX).
 *   <li>Flow-control implementations that back off the sending rate on {@code EAGAIN} are correct;
 *       assert that the back-off reduces the send rate and does not cause the sender to drop messages.
 *   <li>Assert that {@code EAGAIN} from {@code send} does not cause the connection to be closed;
 *       the connection is still valid and data can be sent once the buffer drains.
 * </ul>
 *
 * <p>In production, {@code EAGAIN} from {@code send} occurs when a slow receiver does not drain
 * its receive buffer fast enough, causing TCP's flow control to reduce the sender's window until
 * the send buffer fills. This is the normal TCP back-pressure mechanism and is a correct and
 * expected operating condition for high-throughput data transfer.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel's TCP stack maintains a per-socket send buffer (controlled by {@code SO_SNDBUF})
 * that stores data waiting to be acknowledged by the remote peer. When the buffer is full (either
 * because the peer's receive window is zero or because the buffer size limit is reached), a
 * non-blocking {@code send} returns {@code EAGAIN}. A blocking {@code send} would block until
 * space becomes available; the injection simulates the non-blocking case regardless of the actual
 * socket mode.
 *
 * <p>Event-driven frameworks (Netty, Vert.x) use edge-triggered {@code epoll} (EPOLLET) for high
 * performance; on edge-triggered sockets, {@code EAGAIN} is the signal to stop sending and wait
 * for the next EPOLLOUT event. Frameworks that use level-triggered epoll receive EPOLLOUT
 * continuously while the buffer has space, which requires a different handling strategy. This
 * injection tests that the framework's write path correctly handles the non-blocking constraint
 * regardless of the epoll mode.
 *
 * <p>Java's NIO {@code SocketChannel.write()} returns 0 when the kernel would return {@code EAGAIN},
 * rather than throwing an exception. Application code that calls {@code write()} in a loop until
 * the buffer is consumed must handle the 0-return case by registering for {@code OP_WRITE}
 * readiness and waiting for the selector to indicate writeability again.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSendEagain(toxicity = 0.05)
 * class SendEagainTest {
 *   @Test
 *   void eventLoopReRegistersForWritabilityOnSendEagain(ConnectionInfo info) {
 *     // assert that the event loop re-arms OP_WRITE and data is eventually delivered
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSendEconnreset
 * @see ChaosSendEpipe
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosSendEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.SEND, errno = Errno.EAGAIN)
public @interface ChaosSendEagain {

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
   * @ChaosSendEagain(id = "primary",  probability = 0.001)
   * @ChaosSendEagain(id = "replica",  probability = 0.01)
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
    ChaosSendEagain[] value();
  }
}
