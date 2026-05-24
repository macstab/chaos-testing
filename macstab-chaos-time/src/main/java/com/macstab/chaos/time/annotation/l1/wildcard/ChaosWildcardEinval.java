/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.wildcard;

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
 * Injects {@code EINVAL} into every interposed time syscall ({@code clock_gettime}, {@code
 * nanosleep}, {@code usleep}), causing each to return {@code -1} with {@code errno = EINVAL} as if
 * the kernel rejected an invalid argument.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code WILDCARD}, errno = {@code
 * EINVAL}) tuple. The {@code WILDCARD} selector matches all three interposed time syscalls
 * simultaneously — equivalent to applying {@link ChaosClockGettimeEinval}, {@link
 * ChaosNanosleepEinval}, and {@link ChaosUsleepEinval} in a single annotation. No runtime
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
 *   <li>On every intercepted call to any of the three syscalls a Bernoulli trial with probability
 *       {@link #probability} is conducted independently.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EINVAL}
 *       without performing any real work.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>All three time syscalls may simultaneously return {@code EINVAL}; this is a hard error for
 *       all three — callers must not retry {@code EINVAL} indefinitely.
 *   <li>Application code that handles {@code EINTR} as the only time-syscall error and treats any
 *       other failure as fatal will crash or abort under this injection.
 *   <li>Assert that the application handles {@code EINVAL} as a programming error, logs it clearly,
 *       and does not enter an infinite retry loop.
 * </ul>
 *
 * <p>In production, simultaneous {@code EINVAL} across all time syscalls indicates a systematic
 * argument construction bug — the kind of bug that only manifests on architectures or kernel
 * versions with stricter argument validation.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code WILDCARD} selector applies the injection uniformly to all three interposed time
 * syscalls. For {@code clock_gettime}, {@code EINVAL} means an invalid clock id; for {@code
 * nanosleep}, it means an out-of-range nanosecond value; for {@code usleep}, it means a microsecond
 * value ≥ 1,000,000. The wildcard form exercises all three without requiring separate annotations
 * for each.
 *
 * <p>This annotation is most powerful when testing the "unknown error" handling posture of
 * applications: code that hard-codes only the expected success and {@code EINTR} cases for time
 * syscalls will mishandle the {@code EINVAL} returned by this injection. That is precisely the
 * latent bug being exercised.
 *
 * <p>Sibling per-syscall annotations ({@link ChaosClockGettimeEinval}, {@link
 * ChaosNanosleepEinval}, {@link ChaosUsleepEinval}) allow targeted injection to a single syscall.
 * Use the wildcard form when testing system-wide argument-validation hardening.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosWildcardEinval(probability = 0.005)
 * class WildcardEinvalTest {
 *   @Test
 *   void applicationHandlesInvalidArgumentAcrossAllTimeSyscalls(ConnectionInfo info) {
 *     // assert that no retry loop spins on EINVAL and that structured errors are emitted
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEinval
 * @see ChaosNanosleepEinval
 * @see ChaosUsleepEinval
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosWildcardEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.WILDCARD, errno = TimeErrno.EINVAL)
public @interface ChaosWildcardEinval {

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
   * @ChaosWildcardEinval(id = "primary",  probability = 0.001)
   * @ChaosWildcardEinval(id = "replica",  probability = 0.01)
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
    ChaosWildcardEinval[] value();
  }
}
