/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.nanosleep;

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
 * Injects {@code EINVAL} into {@code nanosleep(2)}, causing the call to return {@code -1} with
 * {@code errno = EINVAL} as if the requested sleep duration contained invalid nanosecond values.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code NANOSLEEP}, errno = {@code EINVAL})
 * tuple. The tuple is safe by construction — {@code EINVAL} is a documented POSIX result of
 * {@code nanosleep(2)} when the {@code tv_nsec} field is not in the range [0, 999999999] or
 * {@code tv_sec} is negative. No runtime selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code nanosleep} call a Bernoulli trial with probability
 *       {@link #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EINVAL}
 *       without sleeping — the sleep is skipped entirely.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep returns immediately with an error; back-pressure loops that rely on {@code nanosleep}
 *       for pacing will spin at full CPU if they do not check the return value.
 *   <li>Rate-limiting components that convert duration objects to nanosecond pairs may produce
 *       values slightly out of range due to integer overflow or unit-conversion bugs; this
 *       annotation surfaces those bugs.
 *   <li>Assert that the application does not enter a busy-spin on {@code EINVAL} and that it
 *       falls back to a bounded sleep using a corrected duration.
 * </ul>
 *
 * <p>In production, {@code EINVAL} from {@code nanosleep} indicates a programming error — the
 * {@code timespec} struct was constructed with an out-of-range nanosecond value — rather than a
 * kernel or hardware failure.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX (IEEE Std 1003.1-2017, {@code nanosleep}) requires {@code tv_nsec} to be in the range
 * {@code [0, 999999999]}. A value of {@code 1_000_000_000} (one billion) is exactly one tick above
 * the legal maximum and triggers {@code EINVAL}; this is a common off-by-one in code that
 * computes durations by dividing nanoseconds and storing the remainder incorrectly.
 *
 * <p>The glibc wrapper does not normalise the struct before passing it to the kernel; it is the
 * kernel's responsibility to validate the range. {@code libchaos-time.so} injects the failure
 * before the kernel boundary, making it impossible to distinguish from the real kernel validation.
 *
 * <p>This injection is most useful against custom timer utilities, retry schedulers, and
 * rate-limiters that build {@code timespec} structs from user-provided inputs. Java's
 * {@code Thread.sleep(long)} internally calls {@code nanosleep} and passes a well-formed struct,
 * so it is not directly affected; custom JNI code and native libraries are the primary targets.
 *
 * <p>Sibling annotations: {@link ChaosNanosleepEintr} targets signal interruption;
 * {@link ChaosNanosleepEfault} targets bad pointer arguments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosNanosleepEinval(probability = 0.01)
 * class NanosleepEinvalTest {
 *   @Test
 *   void rateLimiterDoesNotSpinOnInvalidTimespec(ConnectionInfo info) {
 *     // assert that the limiter detects EINVAL and applies a fallback bounded delay
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosNanosleepEintr
 * @see ChaosNanosleepEfault
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosNanosleepEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.NANOSLEEP, errno = TimeErrno.EINVAL)
public @interface ChaosNanosleepEinval {

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
   * @ChaosNanosleepEinval(id = "primary",  probability = 0.001)
   * @ChaosNanosleepEinval(id = "replica",  probability = 0.01)
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
    ChaosNanosleepEinval[] value();
  }
}
