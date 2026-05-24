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
 * Injects {@code EFAULT} into {@code usleep(3)}, causing the call to return {@code -1} with {@code
 * errno = EFAULT} as if the underlying sleep mechanism received an invalid address argument.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code USLEEP}, errno = {@code EFAULT})
 * tuple. The tuple is safe by construction — {@code EFAULT} is a valid POSIX error indicating a bad
 * address was passed to an underlying system call. No runtime selector-errno validation is needed.
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EFAULT}
 *       without sleeping — the sleep is aborted immediately.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep is skipped; callers that proceed without checking the return value will not apply
 *       the intended back-off delay, potentially causing excessive retry rates.
 *   <li>Embedded C libraries using {@code usleep} in retry loops that do not guard against any
 *       non-zero return will busy-spin under this injection.
 *   <li>Assert that the application treats unexpected errors from {@code usleep} as non-retriable
 *       and applies a safe fallback delay.
 * </ul>
 *
 * <p>In production, {@code EFAULT} from {@code usleep} indicates stack corruption or an invalid
 * internal pointer in the glibc wrapper's transition to {@code nanosleep}. It is extremely rare on
 * healthy systems and is primarily useful for testing defensive code paths.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The glibc implementation of {@code usleep(3)} converts the microsecond argument to a {@code
 * timespec} on the stack and calls {@code nanosleep}. Because the {@code timespec} is a
 * stack-allocated struct, {@code EFAULT} can only arise if the stack itself is inaccessible — a
 * near-crash condition. {@code libchaos-time.so} injects the error synthetically, allowing tests to
 * cover the error-handling path without corrupting the process state.
 *
 * <p>This annotation is most useful when testing native JNI shims that call {@code usleep}
 * indirectly, or C shared libraries where the {@code usleep} call may occur with an unusual stack
 * layout (e.g. a thread with a very small stack size that has nearly exhausted its space).
 *
 * <p>Sibling annotations: {@link ChaosUsleepEintr} targets signal interruption — the far more
 * common case; {@link ChaosNanosleepEfault} applies the equivalent injection to the modern {@code
 * nanosleep} interface.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosUsleepEfault(probability = 0.001)
 * class UsleepEfaultTest {
 *   @Test
 *   void nativeLibraryHandlesUsleepFaultGracefully(ConnectionInfo info) {
 *     // assert that the JNI wrapper does not crash or busy-spin on EFAULT
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosUsleepEintr
 * @see ChaosNanosleepEfault
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosUsleepEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.USLEEP, errno = TimeErrno.EFAULT)
public @interface ChaosUsleepEfault {

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
   * @ChaosUsleepEfault(id = "primary",  probability = 0.001)
   * @ChaosUsleepEfault(id = "replica",  probability = 0.01)
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
    ChaosUsleepEfault[] value();
  }
}
