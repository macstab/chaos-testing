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
 * Injects {@code ENOSYS} into {@code usleep(3)}, causing the call to return {@code -1} with {@code
 * errno = ENOSYS} as if the underlying sleep syscall was absent from the kernel.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code USLEEP}, errno = {@code ENOSYS})
 * tuple. The tuple is safe by construction — {@code ENOSYS} is a valid POSIX result on kernels that
 * do not implement the underlying sleep syscall. No runtime selector-errno validation is needed.
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = ENOSYS}
 *       without sleeping — the call appears entirely unsupported.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep returns immediately with an error; callers that do not fall back to an
 *       alternative sleep primitive will proceed without any delay.
 *   <li>Libraries compiled against feature-detection macros that assume {@code usleep} is always
 *       available may not have a fallback path; this injection reveals those gaps.
 *   <li>Assert that the application selects an alternative sleep mechanism and emits a diagnostic
 *       warning at startup rather than crashing or busy-spinning.
 * </ul>
 *
 * <p>In production, {@code ENOSYS} from {@code usleep} appears on extremely minimal kernels or
 * POSIX emulation layers; it is primarily useful for testing that capability-detection logic in C
 * libraries is correct.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code usleep(3)} is a POSIX function, obsolete since POSIX.1-2008, implemented in glibc via
 * {@code nanosleep(2)}. If the kernel lacks {@code nanosleep}, glibc falls back to {@code select}
 * with a timeout. {@code libchaos-time.so} interposes at the {@code usleep} symbol level and
 * returns {@code ENOSYS} before any fallback logic can run, simulating a total absence of sleep
 * support.
 *
 * <p>C libraries that use {@code usleep} without a fallback will fail silently in this scenario.
 * Most modern applications prefer {@code nanosleep} or {@code clock_nanosleep}; this annotation is
 * most useful for testing legacy code paths or embedded C libraries bundled in JNI shims.
 *
 * <p>Sibling annotations: {@link
 * com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepEnosys} applies the same injection
 * to the modern {@code nanosleep} interface; {@link ChaosUsleepEintr} targets the common
 * signal-interruption case.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosUsleepEnosys(probability = 1.0)
 * class UsleepEnosysTest {
 *   @Test
 *   void embeddedLibraryFallsBackToNanosleepWhenUsleepAbsent(ConnectionInfo info) {
 *     // assert that the embedded library selects nanosleep or select as fallback
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepEnosys
 * @see ChaosUsleepEintr
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosUsleepEnosys.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.USLEEP, errno = TimeErrno.ENOSYS)
public @interface ChaosUsleepEnosys {

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
   * @ChaosUsleepEnosys(id = "primary",  probability = 0.001)
   * @ChaosUsleepEnosys(id = "replica",  probability = 0.01)
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
    ChaosUsleepEnosys[] value();
  }
}
