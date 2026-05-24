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
 * Injects {@code EINTR} into {@code usleep(3)}, causing the call to return {@code -1} with {@code
 * errno = EINTR} as if a signal interrupted the microsecond sleep before completion.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code USLEEP}, errno = {@code EINTR})
 * tuple. The tuple is safe by construction — {@code EINTR} is the primary documented POSIX result
 * of {@code usleep(3)} when a signal is delivered to the process during the sleep. No runtime
 * selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code usleep} call a Bernoulli trial with probability {@link
 *       #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EINTR}
 *       without sleeping — the sleep is cut short with a signal-interrupted indication.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep returns immediately with an error; callers that do not retry will not sleep for
 *       the intended duration, causing pacing loops to run faster than intended.
 *   <li>C-level libraries embedded in native code called from JNI that use {@code usleep} for retry
 *       back-off may react incorrectly to the interrupted sleep.
 *   <li>Unlike {@code nanosleep}, {@code usleep} does not populate a remaining-time struct; callers
 *       must track elapsed time independently to restart correctly.
 *   <li>Assert that the application restarts the sleep or falls back to a bounded alternative and
 *       does not skip the intended back-off interval.
 * </ul>
 *
 * <p>In production, {@code EINTR} from {@code usleep} is a common failure mode in C libraries that
 * use it for polling delays; monitoring agents and database client libraries frequently embed
 * {@code usleep}-based retry loops that must restart on signal interruption.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code usleep(3)} is implemented on Linux via {@code nanosleep(2)} or {@code select(2)}; glibc
 * converts the microsecond argument to a {@code timespec} and calls {@code nanosleep}. Both paths
 * are susceptible to {@code EINTR} when a signal is delivered during the sleep. {@code
 * libchaos-time.so} interposes {@code usleep} directly, not its underlying implementation, to
 * ensure the injection is visible at the C library level regardless of the implementation path.
 *
 * <p>POSIX marks {@code usleep} as obsolescent; the preferred replacement is {@code nanosleep}.
 * However, numerous production C libraries (libcurl, OpenSSL, librdkafka) still use {@code usleep}
 * internally. {@code libchaos-time.so} covers both the modern and legacy interfaces.
 *
 * <p>Sibling annotation: {@link ChaosNanosleepEintr} applies the same injection to the modern
 * {@code nanosleep} interface; {@link ChaosUsleepLatency} extends the sleep duration without
 * cutting it short — the opposite failure mode.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosUsleepEintr(probability = 0.05)
 * class UsleepEintrTest {
 *   @Test
 *   void clientLibraryRetriesBackoffSleepAfterSignalInterruption(ConnectionInfo info) {
 *     // assert that the retry interval is still applied despite the interruption
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosNanosleepEintr
 * @see ChaosUsleepLatency
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosUsleepEintr.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.USLEEP, errno = TimeErrno.EINTR)
public @interface ChaosUsleepEintr {

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
   * @ChaosUsleepEintr(id = "primary",  probability = 0.001)
   * @ChaosUsleepEintr(id = "replica",  probability = 0.01)
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
    ChaosUsleepEintr[] value();
  }
}
