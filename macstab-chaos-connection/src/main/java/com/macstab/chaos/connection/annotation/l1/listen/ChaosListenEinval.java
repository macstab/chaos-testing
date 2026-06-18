/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.listen;

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
 * Injects {@code EINVAL} into {@code listen(2)}, causing the call to return {@code -1} with {@code
 * errno = EINVAL} as if the socket is already connected or the backlog value is negative,
 * indicating a programming error in the socket setup sequence.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code LISTEN}, errno = {@code
 * EINVAL}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code listen} call; when it fires the interposer returns {@code -1} with {@code errno = EINVAL}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code listen} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EINVAL}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EINVAL} from {@code listen} indicates a programming error — the socket is already
 *       connected, or the backlog parameter is out of range; applications must treat this as a
 *       non-retriable fatal error and terminate the server startup sequence.
 *   <li>Assert that server frameworks do not retry {@code listen} on {@code EINVAL}; the underlying
 *       condition (already connected, invalid backlog) cannot resolve itself, so retrying produces
 *       the same error indefinitely.
 *   <li>Assert that the application logs the error at FATAL level with the socket's file descriptor
 *       number and the backlog value it attempted to pass to {@code listen}, providing enough
 *       context for diagnosis.
 *   <li>Distinguish {@code EINVAL} from {@code EOPNOTSUPP}: both are non-retriable but for
 *       different reasons — {@code EINVAL} is a parameter or state error; {@code EOPNOTSUPP} means
 *       the socket type fundamentally does not support listening.
 * </ul>
 *
 * <p>In production, {@code EINVAL} from {@code listen} occurs when a socket is converted from a
 * connected socket (after {@code accept}) back into a listening socket without going through {@code
 * socket} + {@code bind} + {@code listen} again, when the backlog value is derived from a
 * misconfigured environment variable that produces a negative number, and in some kernel versions
 * when {@code listen} is called on an already-listening socket.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies that {@code listen} shall fail with {@code EINVAL} when the socket is already
 * connected. Linux additionally returns {@code EINVAL} if the backlog argument is a very large
 * negative value (though positive values are clamped to the kernel limit set by {@code
 * net.core.somaxconn}, defaults to 128 in most distributions, 65535 on recent kernels).
 *
 * <p>The backlog parameter controls the depth of the kernel's completed-connection queue (the queue
 * of connections that have completed the TCP three-way handshake but have not yet been accepted by
 * the application). An undersized backlog causes the kernel to reject new SYN packets when the
 * queue is full, resulting in client-side connection timeouts. However, passing a negative backlog
 * is a programming error caught by the kernel before queue sizing.
 *
 * <p>Java's {@code ServerSocket} validates the backlog before calling {@code listen}: a backlog of
 * 0 or negative is converted to the implementation's default (50 in the OpenJDK implementation).
 * This means Java applications are unlikely to see {@code EINVAL} from an invalid backlog; the
 * injection tests the error-handling path that would fire if a native library bypasses Java's
 * backlog validation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosListenEinval(toxicity = 0.001)
 * class ListenEinvalTest {
 *   @Test
 *   void serverRecognisesListenEinvalAsNonRetriableAndShutDown(ConnectionInfo info) {
 *     // assert that startup fails fatally without retry on EINVAL from listen
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosListenEaddrinuse
 * @see ChaosListenEopnotsupp
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosListenEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.LISTEN, errno = Errno.EINVAL)
public @interface ChaosListenEinval {

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
   * @ChaosListenEinval(id = "primary",  probability = 0.001)
   * @ChaosListenEinval(id = "replica",  probability = 0.01)
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
    ChaosListenEinval[] value();
  }
}
