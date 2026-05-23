/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.clock_gettime;

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
 * Injects {@code ENOSYS} into {@code clock_gettime(2)}, causing the call to return {@code -1} with
 * {@code errno = ENOSYS} as if the kernel did not implement the syscall at all.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code CLOCK_GETTIME}, errno =
 * {@code ENOSYS}) tuple. The tuple is safe by construction — {@code ENOSYS} is a documented POSIX
 * result of {@code clock_gettime(2)} on kernels that were compiled without POSIX clock support.
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
 *   <li>On every intercepted {@code clock_gettime} call a Bernoulli trial with probability
 *       {@link #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = ENOSYS}
 *       without invoking the real kernel call — the application sees a genuine "not implemented"
 *       failure.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code clock_gettime} returns {@code -1} with {@code ENOSYS}; callers that fall back to
 *       {@code gettimeofday} or {@code time()} will use a lower-resolution timestamp.
 *   <li>Java's {@code System.nanoTime()} may fall back to the OS monotonic clock alternative or
 *       throw if the JVM does not handle the absent syscall gracefully.
 *   <li>Libraries such as Micrometer that select a clock implementation at startup may permanently
 *       downgrade to a lower-precision alternative, affecting histogram accuracy.
 *   <li>Assert that the application selects an appropriate fallback clock and emits a warning
 *       rather than crashing at startup or later under load.
 * </ul>
 *
 * <p>In production, {@code ENOSYS} from {@code clock_gettime} appears in extremely old or minimal
 * kernels (pre-2.6.28), uClinux environments, or emulated POSIX layers where POSIX clocks are
 * not compiled in. It is a rare but legitimate edge case for software that must run on embedded
 * or legacy hosts.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>On a standard Linux kernel, {@code clock_gettime} is always present; {@code ENOSYS} therefore
 * simulates a kernel that does not export the syscall in its syscall table. The glibc vDSO fast
 * path would also fail in this case because the vDSO itself depends on the kernel exporting the
 * relevant symbols. {@code libchaos-time.so} injects the failure before either path is attempted.
 *
 * <p>Glibc itself checks for {@code ENOSYS} on first use and falls back to {@code gettimeofday(2)}
 * for {@code CLOCK_REALTIME}. Binaries compiled against musl that statically link the time calls
 * do not have this fallback and will propagate the error directly to the caller.
 *
 * <p>This injection is most useful for verifying that frameworks perform capability discovery at
 * startup (checking which clocks are available) rather than assuming all POSIX clocks are always
 * present. Spring Boot's {@code ApplicationContext} startup phase, Micrometer's clock selection,
 * and custom high-resolution timer utilities are the primary targets.
 *
 * <p>Sibling annotations: {@link ChaosClockGettimeEinval} targets unknown clock ids;
 * {@link ChaosClockGettimeEfault} targets bad output pointers; {@link ChaosClockGettimeEperm}
 * targets capability failures.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeEnosys(probability = 1.0)
 * class ClockGettimeEnosysTest {
 *   @Test
 *   void applicationSelectsFallbackClockWhenSyscallAbsent(ConnectionInfo info) {
 *     // assert that the application selects gettimeofday or equivalent and logs a warning
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEinval
 * @see ChaosClockGettimeEfault
 * @see ChaosClockGettimeEperm
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosClockGettimeEnosys.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.CLOCK_GETTIME, errno = TimeErrno.ENOSYS)
public @interface ChaosClockGettimeEnosys {

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
   * @ChaosClockGettimeEnosys(id = "primary",  probability = 0.001)
   * @ChaosClockGettimeEnosys(id = "replica",  probability = 0.01)
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
    ChaosClockGettimeEnosys[] value();
  }
}
