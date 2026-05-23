/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.bind;

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
 * Injects {@code EINVAL} into {@code bind(2)}, causing the call to return {@code -1} with
 * {@code errno = EINVAL} as if the socket is already bound to an address or the address structure
 * length is incorrect for the socket's address family.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code BIND}, errno = {@code EINVAL})
 * tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code bind} call; when it fires the interposer returns {@code -1} with {@code errno = EINVAL}
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
 *   <li>On each intercepted {@code bind} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EINVAL}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EINVAL} from {@code bind} indicates a programming error: the socket is already bound,
 *       the address-family does not match the socket type, or the address length is incorrect.
 *       Applications must treat this as a non-retriable fatal error.
 *   <li>Assert that the application does not retry on {@code EINVAL}; retrying will produce the same
 *       error indefinitely because the underlying condition (double-bind or mismatched address
 *       family) cannot resolve itself.
 *   <li>Assert that the application logs the error at ERROR or FATAL level with enough context to
 *       identify the offending socket and address, so that operators can diagnose the configuration
 *       mismatch.
 *   <li>Server frameworks that catch all bind errors and retry will spin indefinitely on
 *       {@code EINVAL}; this injection verifies that the framework has a non-retriable error path.
 * </ul>
 *
 * <p>In production, {@code EINVAL} from {@code bind} occurs when application code calls {@code bind}
 * twice on the same socket (a common mistake when a socket is reused across requests), when the
 * address structure is constructed for the wrong address family (e.g., passing an IPv6 address
 * structure to an IPv4 socket), or when a helper library wraps socket setup incorrectly.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The Linux kernel returns {@code EINVAL} from {@code bind} in three main cases: the socket is
 * already bound to an address (calling {@code bind} a second time is forbidden by POSIX); the
 * {@code addrlen} argument does not match the size expected for the socket's address family (e.g.,
 * passing {@code sizeof(struct sockaddr_in)} for a {@code PF_INET6} socket); or the socket has been
 * shut down (some kernel versions return {@code EINVAL} for operations on shut-down sockets).
 *
 * <p>The "already bound" case is the most common in practice and is always a programming error.
 * POSIX specifies that once bound, a socket's local address is fixed for its lifetime; the address
 * can only be changed by closing and re-creating the socket. Connection pooling libraries that
 * attempt to reuse sockets by calling {@code bind} again will encounter this error; the injection
 * tests that the pool correctly detects the error and creates a new socket rather than retrying
 * {@code bind} on the existing one.
 *
 * <p>Java's {@code ServerSocket} and {@code DatagramSocket} map {@code EINVAL} from {@code bind} to
 * a {@code SocketException} with the message "Invalid argument". Application code that catches
 * {@code SocketException} generically and retries will retry on {@code EINVAL} indefinitely. This
 * injection verifies that the retry logic is conditioned on retriable errors (e.g., {@code EINTR})
 * rather than all {@code SocketException} subtypes.
 *
 * <p>This is the {@code bind}-specific analogue of {@code EINVAL} from {@code accept} and
 * {@code listen}: all three indicate a socket lifecycle invariant violation. The correct response in
 * all cases is to close the socket, create a new one, and restart the socket setup sequence from
 * the beginning.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosBindEinval(toxicity = 0.001)
 * class BindEinvalTest {
 *   @Test
 *   void serverRecognisesEinvalAsNonRetriableAndFailsFast(ConnectionInfo info) {
 *     // assert that the server does not retry bind and logs the error at FATAL level
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosBindEaddrinuse
 * @see ChaosBindEaddrnotavail
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosBindEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.BIND, errno = Errno.EINVAL)
public @interface ChaosBindEinval {

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
   * @ChaosBindEinval(id = "primary",  probability = 0.001)
   * @ChaosBindEinval(id = "replica",  probability = 0.01)
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
    ChaosBindEinval[] value();
  }
}
