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
 * Injects {@code ENOSYS} into {@code nanosleep(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOSYS} as if the kernel did not implement the syscall.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code NANOSLEEP}, errno = {@code
 * ENOSYS}) tuple. The tuple is safe by construction — {@code ENOSYS} is a valid POSIX result on
 * minimal kernels that do not implement {@code nanosleep}. No runtime selector-errno validation is
 * needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code nanosleep} call a Bernoulli trial with probability {@link
 *       #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = ENOSYS}
 *       without sleeping — the call appears to be unsupported by the kernel.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep returns immediately; callers that fall back to {@code usleep} or {@code select}
 *       will continue with lower precision; callers without a fallback will abort.
 *   <li>Startup probes that detect clock capabilities at boot time should detect the absence of
 *       {@code nanosleep} and select an alternative sleep primitive.
 *   <li>Assert that the application selects an appropriate fallback and emits a warning rather than
 *       crashing or busy-spinning.
 * </ul>
 *
 * <p>In production, {@code ENOSYS} from {@code nanosleep} appears on uClinux embedded kernels
 * compiled without {@code nanosleep} support, on old kernel versions (&lt;2.0), and in some POSIX
 * emulation layers on non-Linux systems.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code nanosleep(2)} has been present in the Linux kernel since version 2.0; encountering
 * {@code ENOSYS} on a modern Linux host indicates a heavily stripped kernel or a missing syscall
 * table entry. The glibc fallback path for {@code ENOSYS} uses {@code select(2)} with a timeout to
 * approximate the sleep duration; however, the precision is much lower.
 *
 * <p>Applications that statically detect the availability of {@code nanosleep} at compile time (via
 * feature-test macros) and do not check the runtime return value will silently fail to sleep when
 * the syscall is absent, resulting in CPU-bound busy behavior.
 *
 * <p>Sibling annotations: {@link ChaosNanosleepEintr} targets the common signal-interruption case;
 * {@link com.macstab.chaos.time.annotation.l1.usleep.ChaosUsleepEnosys} applies the same injection
 * to the legacy {@code usleep(3)} wrapper.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosNanosleepEnosys(probability = 1.0)
 * class NanosleepEnosysTest {
 *   @Test
 *   void applicationSelectsFallbackSleepWhenNanosleepAbsent(ConnectionInfo info) {
 *     // assert that the application selects an alternative sleep mechanism and continues
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosNanosleepEintr
 * @see com.macstab.chaos.time.annotation.l1.usleep.ChaosUsleepEnosys
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosNanosleepEnosys.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.NANOSLEEP, errno = TimeErrno.ENOSYS)
public @interface ChaosNanosleepEnosys {

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
   * @ChaosNanosleepEnosys(id = "primary",  probability = 0.001)
   * @ChaosNanosleepEnosys(id = "replica",  probability = 0.01)
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
    ChaosNanosleepEnosys[] value();
  }
}
