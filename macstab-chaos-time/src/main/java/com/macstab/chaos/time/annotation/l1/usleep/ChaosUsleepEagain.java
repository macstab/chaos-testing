/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.usleep;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.time.annotation.l1.TimeErrnoBinding;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeSelector;

/**
 * Injects {@code EAGAIN} into {@code usleep(3)}, causing the call to return {@code -1} with
 * {@code errno = EAGAIN} as if a temporary resource constraint prevented the sleep.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code USLEEP}, errno = {@code EAGAIN})
 * tuple. The tuple is safe by construction — {@code EAGAIN} is a valid transient POSIX error
 * indicating resource temporarily unavailable; injecting it exercises defensive retry paths in
 * C-level sleep loops. No runtime selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code usleep} call a Bernoulli trial with probability
 *       {@link #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EAGAIN}
 *       without sleeping — the sleep is skipped.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Back-pressure loops using {@code usleep} as a pacing primitive will spin faster than
 *       intended when {@code EAGAIN} is returned, potentially overwhelming downstream services.
 *   <li>C libraries that treat any non-zero return from {@code usleep} as a signal to retry
 *       immediately will busy-spin when the injection rate is high.
 *   <li>Assert that the application distinguishes retryable errors from permanent failures and
 *       applies a bounded delay before re-attempting the sleep.
 * </ul>
 *
 * <p>In production, {@code EAGAIN} from {@code usleep} does not occur on standard Linux kernels.
 * This injection exercises the general error-handling posture of callers that check the return
 * value but do not validate the specific errno before deciding to retry.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Standard Linux kernels do not return {@code EAGAIN} from {@code nanosleep} (the underlying
 * implementation of {@code usleep}); the sleep always either completes or is interrupted by a
 * signal. The injection via {@code libchaos-time.so} creates a synthetic transient error to
 * test code paths that use a generic {@code errno != EINVAL} guard before retrying.
 *
 * <p>Such patterns are common in legacy C libraries: {@code while (usleep(n) != 0 && errno != EINVAL) usleep(n);}
 * This loop retries on any non-EINVAL error, including {@code EAGAIN}, potentially leading to
 * a busy-loop if the injection rate is high enough.
 *
 * <p>Sibling annotations: {@link ChaosUsleepEintr} is the far more realistic signal-interruption
 * case; {@link ChaosNanosleepEagain} applies the equivalent injection to the modern interface.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosUsleepEagain(probability = 1e-3)
 * class UsleepEagainTest {
 *   @Test
 *   void paceLoopDoesNotBusySpinOnTransientUsleepFailure(ConnectionInfo info) {
 *     // assert that CPU usage remains bounded and the pace is approximately correct
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosUsleepEintr
 * @see ChaosNanosleepEagain
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosUsleepEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.USLEEP, errno = TimeErrno.EAGAIN)
public @interface ChaosUsleepEagain {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-time
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosUsleepEagain(id = "primary",  probability = 0.001)
   * @ChaosUsleepEagain(id = "replica",  probability = 0.01)
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
    ChaosUsleepEagain[] value();
  }
}
