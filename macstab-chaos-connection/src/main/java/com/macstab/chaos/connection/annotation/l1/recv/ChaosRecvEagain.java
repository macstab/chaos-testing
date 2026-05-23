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
 * Injects {@code EAGAIN} into {@code recv(2)}, causing the call to return {@code -1} with
 * {@code errno = EAGAIN} as if the socket is in non-blocking mode and no data is currently
 * available in the receive buffer.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RECV}, errno = {@code EAGAIN})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code recv} call; when it fires the interposer returns {@code -1} with {@code errno = EAGAIN}
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
 *       {@code errno = EAGAIN}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Non-blocking I/O implementations must treat {@code EAGAIN} as a signal to re-register
 *       for read readiness and wait; assert that the event loop correctly re-arms the readiness
 *       notification rather than spinning in a tight retry loop.
 *   <li>Blocking I/O implementations on non-blocking sockets use {@code poll}/{@code select}
 *       before calling {@code recv}; if the blocking layer does not handle {@code EAGAIN}
 *       transparently, assert that the error is surfaced correctly to the caller.
 *   <li>Assert that the application does not treat {@code EAGAIN} from {@code recv} as a
 *       connection error; the connection is still valid and data will arrive later.
 *   <li>Protocol parsers that accumulate partial reads across multiple {@code recv} calls must
 *       preserve their partial state on {@code EAGAIN}; assert that parser state is consistent
 *       after a spurious EAGAIN during a multi-read sequence.
 * </ul>
 *
 * <p>In production, {@code EAGAIN} from {@code recv} on a non-blocking socket is the normal
 * "no data available" signal in event-driven servers; it is not an error condition. This injection
 * introduces the signal at times when data is actually available in the kernel receive buffer, which
 * tests the retry logic of code that calls {@code recv} without a preceding {@code poll}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX defines {@code EAGAIN} and {@code EWOULDBLOCK} as interchangeable on Linux (they are the
 * same value, 11). When a non-blocking socket has no data in the receive buffer, {@code recv}
 * returns {@code EAGAIN} immediately rather than blocking. Event-driven frameworks rely on this
 * behaviour: they call {@code recv} after {@code epoll}/{@code poll} indicates the socket is
 * readable, and expect either data or {@code EAGAIN}. The injection can cause {@code EAGAIN} to
 * appear even after the socket was indicated as readable by poll, which should be handled by
 * re-registering for readiness (this is normal under level-triggered epoll but unusual under
 * edge-triggered epoll).
 *
 * <p>Blocking sockets can also return {@code EAGAIN} if {@code SO_RCVTIMEO} is set and the timeout
 * fires before any data arrives, but this is typically mapped to {@code EAGAIN} or
 * {@code EWOULDBLOCK}. Java maps {@code SO_RCVTIMEO}-induced {@code EAGAIN} to a
 * {@code SocketTimeoutException}, not to a generic {@code SocketException}. Application code must
 * therefore handle both exceptions when dealing with read timeouts.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosRecvEagain(toxicity = 0.01)
 * class RecvEagainTest {
 *   @Test
 *   void eventLoopReRegistersForReadinessOnEagain(ConnectionInfo info) {
 *     // assert that the event loop re-arms readiness and data is eventually received
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRecvEconnreset
 * @see ChaosRecvEintr
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosRecvEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.RECV, errno = Errno.EAGAIN)
public @interface ChaosRecvEagain {

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
   * @ChaosRecvEagain(id = "primary",  probability = 0.001)
   * @ChaosRecvEagain(id = "replica",  probability = 0.01)
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
    ChaosRecvEagain[] value();
  }
}
