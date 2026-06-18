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
 * Injects {@code EPERM} into {@code clock_gettime(2)}, causing the call to return {@code -1} with
 * {@code errno = EPERM} as if the kernel denied access to a privileged clock identifier.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code CLOCK_GETTIME}, errno = {@code
 * EPERM}) tuple. The tuple is safe by construction — {@code EPERM} is a documented POSIX result of
 * {@code clock_gettime(2)} when the caller does not hold the capability required to access a
 * process or thread CPU-time clock of another process. No runtime selector-errno validation is
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
 *   <li>On every intercepted {@code clock_gettime} call a Bernoulli trial with probability {@link
 *       #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EPERM}
 *       without invoking the real kernel call — the application sees a genuine capability failure.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code clock_gettime} returns {@code -1}; the struct is not populated; any downstream read
 *       of the uninitialized {@code timespec} produces undefined results.
 *   <li>Applications that use {@code CLOCK_PROCESS_CPUTIME_ID} for profiling in production
 *       containers with dropped Linux capabilities will hit this error at runtime.
 *   <li>Java agents and profilers that sample CPU time via JNI may silently disable themselves or
 *       throw {@link java.lang.RuntimeException} depending on JVM implementation.
 *   <li>Assert that the application degrades gracefully, disables the optional profiling feature,
 *       and emits a diagnostic log rather than crashing the process.
 * </ul>
 *
 * <p>In production, {@code EPERM} from {@code clock_gettime} typically occurs in
 * capability-hardened containers (no {@code CAP_SYS_PTRACE}) that try to access the CPU-time clock
 * of a foreign pid, or in seccomp-restricted environments that block specific clock ids.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Linux kernel source ({@code kernel/posix-cpu-timers.c}) returns {@code EPERM} when {@code
 * clock_gettime} is invoked with a per-process or per-thread clock id derived from a pid that the
 * caller is not permitted to inspect. This is governed by the ptrace permission model: the caller
 * must pass {@code ptrace_may_access(task, PTRACE_MODE_READ)} for the target pid.
 *
 * <p>Containers with a minimal capability set (no {@code CAP_SYS_PTRACE}) will receive {@code
 * EPERM} even when requesting the clock id of their own process if the kernel was compiled with
 * {@code CONFIG_SECURITY_YAMA} and the Yama ptrace scope is set to 3 (no ptrace at all). This is a
 * real production failure mode on hardened Kubernetes nodes.
 *
 * <p>The glibc wrapper and vDSO fast path both bypass the kernel for {@code CLOCK_REALTIME} and
 * {@code CLOCK_MONOTONIC}; only the slower {@code CLOCK_PROCESS_CPUTIME_ID} and per-pid clocks
 * require a real syscall. {@code libchaos-time.so} intercepts all variants uniformly.
 *
 * <p>Sibling annotations: {@link ChaosClockGettimeEinval} targets unknown clock ids; {@link
 * ChaosClockGettimeEfault} targets bad output pointers; {@link ChaosClockGettimeEnosys} targets
 * kernels that lack the syscall entirely.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeEperm(probability = 0.01)
 * class ClockGettimeEpermTest {
 *   @Test
 *   void profilingFeatureDegradesGracefullyWhenCapabilityAbsent(ConnectionInfo info) {
 *     // assert that the profiler disables itself and the application continues normally
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEinval
 * @see ChaosClockGettimeEfault
 * @see ChaosClockGettimeEnosys
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosClockGettimeEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.CLOCK_GETTIME, errno = TimeErrno.EPERM)
public @interface ChaosClockGettimeEperm {

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
   * @ChaosClockGettimeEperm(id = "primary",  probability = 0.001)
   * @ChaosClockGettimeEperm(id = "replica",  probability = 0.01)
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
    ChaosClockGettimeEperm[] value();
  }
}
