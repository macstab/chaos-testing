/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.accept;

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
 * Injects {@code EAGAIN} into {@code accept(2)}, causing the call to return {@code -1} with {@code
 * errno = EAGAIN} as if the listening socket is non-blocking and no connection is currently waiting
 * to be accepted.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ACCEPT}, errno = {@code
 * EAGAIN}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code accept} call; when it fires the interposer returns {@code -1} with {@code errno = EAGAIN}
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
 *   <li>On every intercepted {@code accept} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EAGAIN}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Server-side accept loops must handle {@code EAGAIN} correctly — typically by returning to
 *       the event loop and re-registering the listening socket for readability rather than treating
 *       it as a fatal error.
 *   <li>Blocking-mode servers that call {@code accept} without {@code O_NONBLOCK} should not
 *       receive {@code EAGAIN} in production; the injection surfaces the assumption that {@code
 *       accept} only fails with {@code EAGAIN} on non-blocking sockets.
 *   <li>Accept loops that retry on all non-zero returns without limit will spin under this
 *       injection, revealing the absence of a back-off or error-checking guard.
 *   <li>Assert that the server correctly handles spurious {@code EAGAIN} results and continues
 *       accepting subsequent connections without logging false-alarm errors.
 * </ul>
 *
 * <p>In production, {@code EAGAIN} from {@code accept} occurs when the listening socket has been
 * set to non-blocking mode (a common pattern in event-driven servers) and no connection is in the
 * accept queue at the moment the call is made — a normal occurrence under low inbound traffic.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies that {@code accept(2)} on a non-blocking socket returns {@code EAGAIN} (or the
 * equivalent {@code EWOULDBLOCK}) when no connections are pending. Event-driven server frameworks
 * (Netty, Vert.x, libuv) rely on this behaviour: they call {@code accept} in a loop until {@code
 * EAGAIN} is received, which signals that the accept queue is drained for this epoll event cycle.
 * Injecting {@code EAGAIN} mid-loop tests whether the loop exits cleanly without logging spurious
 * errors or leaving accepted fds unclosed.
 *
 * <p>Blocking-mode servers that use a thread-per-connection model typically call {@code accept} on
 * a blocking socket; they should never receive {@code EAGAIN} in normal operation. When this
 * injection fires against such a server, the application's handling of an unexpected {@code EAGAIN}
 * is exercised — most implementations treat it as a warning and retry, but some incorrectly treat
 * it as a fatal error and shut down the accept loop.
 *
 * <p>Java's {@code ServerSocketChannel} in blocking mode wraps {@code accept} and converts {@code
 * EAGAIN} to a null return (no connection available). In non-blocking mode it throws {@code
 * SocketTimeoutException} or returns null. Application code that inspects the return value rather
 * than catching exceptions will silently discard the connection attempt, which this injection makes
 * visible by observing that the accept loop does not increment its connection counter.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosAcceptEagain(toxicity = 0.01)
 * class AcceptEagainTest {
 *   @Test
 *   void serverAcceptLoopHandlesSpuriousEagainWithoutLoggingErrors(ConnectionInfo info) {
 *     // assert that the server continues accepting and does not log EAGAIN as a warning
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAcceptEconnreset
 * @see ChaosAcceptLatency
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosAcceptEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.ACCEPT, errno = Errno.EAGAIN)
public @interface ChaosAcceptEagain {

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
   * @ChaosAcceptEagain(id = "primary",  probability = 0.001)
   * @ChaosAcceptEagain(id = "replica",  probability = 0.01)
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
    ChaosAcceptEagain[] value();
  }
}
