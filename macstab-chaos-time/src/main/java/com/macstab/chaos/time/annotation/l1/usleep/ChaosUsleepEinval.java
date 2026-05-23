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
 * Injects {@code EINVAL} into {@code usleep(3)}, causing the call to return {@code -1} with
 * {@code errno = EINVAL} as if the microsecond argument exceeded the valid range.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code USLEEP}, errno = {@code EINVAL})
 * tuple. The tuple is safe by construction — {@code EINVAL} is a documented POSIX result of
 * {@code usleep(3)} when the {@code usec} argument is greater than or equal to 1,000,000 (one second).
 * No runtime selector-errno validation is needed.
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EINVAL}
 *       without sleeping — the sleep is rejected.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep is skipped; callers without error handling will proceed immediately without the
 *       intended back-off, producing a busy-loop.
 *   <li>Code that passes {@code usec} values computed from division or scaling may pass values
 *       ≥ 1,000,000 due to integer arithmetic bugs; this annotation surfaces those bugs.
 *   <li>Assert that the application bounds the {@code usec} argument to the legal range before
 *       calling {@code usleep} and handles the error gracefully.
 * </ul>
 *
 * <p>In production, {@code EINVAL} from {@code usleep} indicates a programming error — the
 * microsecond argument was out of the legal range. The POSIX specification requires callers to
 * use {@code nanosleep} for sleep intervals of one second or longer.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The POSIX 2017 specification states that {@code usleep} behavior is undefined when
 * {@code usec >= 1000000}; some implementations raise {@code EINVAL}, others clamp to the maximum.
 * On Linux with glibc, the glibc wrapper converts {@code usec} to a {@code timespec} and calls
 * {@code nanosleep}; since {@code nanosleep} accepts any number of seconds, glibc never actually
 * raises {@code EINVAL} for large values on Linux. {@code libchaos-time.so} injects it anyway
 * to simulate the POSIX-compliant behavior and test that callers do not rely on the glibc extension.
 *
 * <p>This injection is most useful for testing C libraries called from JNI that use {@code usleep}
 * with computed arguments — particularly when the computation involves unit conversion from
 * milliseconds to microseconds and the value can occasionally overflow the 999999 µs limit.
 *
 * <p>Sibling annotations: {@link ChaosUsleepEintr} targets signal interruption;
 * {@link ChaosUsleepEfault} targets bad pointer arguments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosUsleepEinval(probability = 0.01)
 * class UsleepEinvalTest {
 *   @Test
 *   void backoffComputationDoesNotExceedUsleepMaximum(ConnectionInfo info) {
 *     // assert that the computed usec value is always in [0, 999999]
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosUsleepEintr
 * @see ChaosUsleepEfault
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosUsleepEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.USLEEP, errno = TimeErrno.EINVAL)
public @interface ChaosUsleepEinval {

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
   * @ChaosUsleepEinval(id = "primary",  probability = 0.001)
   * @ChaosUsleepEinval(id = "replica",  probability = 0.01)
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
    ChaosUsleepEinval[] value();
  }
}
