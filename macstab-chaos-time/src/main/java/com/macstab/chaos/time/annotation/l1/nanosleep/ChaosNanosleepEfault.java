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
 * Injects {@code EFAULT} into {@code nanosleep(2)}, causing the call to return {@code -1} with
 * {@code errno = EFAULT} as if the {@code req} pointer was invalid or pointed to unmapped memory.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code NANOSLEEP}, errno = {@code EFAULT})
 * tuple. The tuple is safe by construction — {@code EFAULT} is a documented POSIX result of
 * {@code nanosleep(2)} when the {@code req} or {@code rem} argument points to inaccessible memory.
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
 *   <li>On every intercepted {@code nanosleep} call a Bernoulli trial with probability
 *       {@link #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EFAULT}
 *       without sleeping — the sleep is aborted immediately.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep returns immediately; callers that do not handle the error will proceed as if the
 *       sleep completed, potentially busy-spinning or skipping intended back-pressure.
 *   <li>Code that trusts the {@code rem} struct after an error may read from an uninitialised or
 *       corrupted buffer if the caller passes a null {@code rem} pointer that is later dereferenced.
 *   <li>Native wrappers around {@code nanosleep} in JNI code may throw {@link java.lang.Error}
 *       or produce undefined behavior if they do not guard against the {@code EFAULT} case.
 *   <li>Assert that the application handles the error by aborting the sleep and logging a
 *       structured diagnostic rather than crashing or spinning.
 * </ul>
 *
 * <p>In production, {@code EFAULT} from {@code nanosleep} indicates stack corruption or a bad
 * pointer passed from JNI code; it is adjacent to memory-safety vulnerabilities and is rarely
 * handled explicitly in application error paths.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel validates both the {@code req} and (if non-null) the {@code rem} pointers during
 * a {@code nanosleep(2)} call via {@code copy_from_user} and {@code put_user}. If either pointer
 * lies outside the accessible virtual address space, the kernel returns {@code EFAULT} without
 * sleeping at all.
 *
 * <p>In practice, a null {@code req} pointer is the most common source; glibc does not guard
 * against it before calling the kernel, so a null pointer passed from a buggy JNI shim produces
 * a genuine {@code EFAULT}. {@code libchaos-time.so} injects the same error code synthetically,
 * allowing tests to cover the handling path without actually corrupting memory.
 *
 * <p>Sibling annotations: {@link ChaosNanosleepEinval} targets out-of-range nanosecond values;
 * {@link ChaosNanosleepEintr} targets signal interruption — the far more common {@code nanosleep}
 * failure mode in production.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosNanosleepEfault(probability = 0.001)
 * class NanosleepEfaultTest {
 *   @Test
 *   void jniSleepWrapperHandlesBadPointerGracefully(ConnectionInfo info) {
 *     // assert that the JNI wrapper aborts cleanly and does not dereference the bad pointer
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosNanosleepEinval
 * @see ChaosNanosleepEintr
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosNanosleepEfault.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.NANOSLEEP, errno = TimeErrno.EFAULT)
public @interface ChaosNanosleepEfault {

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
   * @ChaosNanosleepEfault(id = "primary",  probability = 0.001)
   * @ChaosNanosleepEfault(id = "replica",  probability = 0.01)
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
    ChaosNanosleepEfault[] value();
  }
}
