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
 * Injects {@code EINVAL} into {@code accept(2)}, causing the call to return {@code -1} with {@code
 * errno = EINVAL} as if the socket is not listening or an invalid address-length argument was
 * provided.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ACCEPT}, errno = {@code
 * EINVAL}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code accept} call; when it fires the interposer returns {@code -1} with {@code errno = EINVAL}
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
 *       errno = EINVAL}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EINVAL} from {@code accept} indicates a programming error — the socket is not in
 *       listening state or the address buffer length is incorrect; servers must not retry on this
 *       error and must surface it as a fatal configuration failure.
 *   <li>Server frameworks that recover from {@code EINVAL} by restarting the accept loop will enter
 *       an infinite error cycle; assert that the framework recognises {@code EINVAL} as
 *       non-retriable and terminates the accept loop.
 *   <li>Assert that the application logs the {@code EINVAL} at ERROR or FATAL level and does not
 *       silently swallow it, since it indicates a socket that is not in the expected state.
 * </ul>
 *
 * <p>In production, {@code EINVAL} from {@code accept} occurs when a socket that has not been put
 * into the listening state (via {@code listen(2)}) is passed to {@code accept}, or when the {@code
 * addrlen} argument is initialised to zero. Both are programming errors rather than transient
 * runtime conditions.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The Linux kernel returns {@code EINVAL} from {@code accept} in two main cases: the socket was
 * not put into the listening state before {@code accept} was called, or the {@code addrlen}
 * argument is negative. Both conditions indicate that the calling code has a logic error — the
 * socket lifecycle invariant (bind → listen → accept) was not followed, or the address buffer was
 * not correctly initialised.
 *
 * <p>Application frameworks that wrap {@code accept} in a retry loop with exponential back-off will
 * retry indefinitely on {@code EINVAL} because the underlying condition (socket not listening)
 * cannot resolve itself without intervention. This injection reveals whether the retry loop
 * correctly distinguishes non-retriable errors from transient ones.
 *
 * <p>Java's {@code ServerSocket.accept()} maps {@code EINVAL} to an {@code IOException} with the
 * message "Invalid argument". Application code that catches all {@code IOException} subtypes and
 * retries will retry on this error; assert that the application applies a max-retry guard or
 * escalates to a fatal error after a fixed number of consecutive failures.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosAcceptEinval(toxicity = 0.001)
 * class AcceptEinvalTest {
 *   @Test
 *   void serverRecognisesEinvalAsNonRetriableAndTerminatesAcceptLoop(ConnectionInfo info) {
 *     // assert that the server does not retry and logs the error at FATAL level
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAcceptEconnreset
 * @see ChaosAcceptEmfile
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosAcceptEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.ACCEPT, errno = Errno.EINVAL)
public @interface ChaosAcceptEinval {

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
   * @ChaosAcceptEinval(id = "primary",  probability = 0.001)
   * @ChaosAcceptEinval(id = "replica",  probability = 0.01)
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
    ChaosAcceptEinval[] value();
  }
}
