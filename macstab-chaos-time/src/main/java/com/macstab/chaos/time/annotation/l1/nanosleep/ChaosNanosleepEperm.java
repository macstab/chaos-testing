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
 * Injects {@code EPERM} into {@code nanosleep(2)}, causing the call to return {@code -1} with
 * {@code errno = EPERM} as if the process lacked permission to use a real-time sleep mechanism.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code NANOSLEEP}, errno = {@code
 * EPERM}) tuple. The tuple is safe by construction — {@code EPERM} is a documented POSIX result of
 * {@code nanosleep(2)} when the process attempts to use a high-resolution sleep that requires
 * elevated privilege on certain POSIX platforms. No runtime selector-errno validation is needed.
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EPERM}
 *       without sleeping — the sleep is aborted immediately.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep returns immediately; retry loops that do not check for {@code EPERM} as a hard
 *       error will busy-spin indefinitely.
 *   <li>Real-time threads that rely on {@code nanosleep} for high-precision pacing may degrade to
 *       unscheduled busy-wait, violating their latency SLA.
 *   <li>Assert that the application treats {@code EPERM} as a non-retriable error, logs the
 *       capability failure, and falls back to a lower-precision sleep mechanism.
 * </ul>
 *
 * <p>In production, {@code EPERM} from {@code nanosleep} appears on POSIX systems (not standard
 * Linux) where high-resolution clocks require a privilege, and in seccomp-filtered environments
 * where the {@code clock_nanosleep} syscall is blocked but the application falls back to {@code
 * nanosleep} which is similarly blocked.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Standard Linux does not return {@code EPERM} from {@code nanosleep} for unprivileged
 * processes. The injection via {@code libchaos-time.so} simulates the behaviour of stricter POSIX
 * systems (e.g. some BSD variants with mandatory access control frameworks) or of seccomp profiles
 * that deny the {@code nanosleep} syscall. The return value mimics what those systems would
 * deliver.
 *
 * <p>Code that uses {@code nanosleep} as a rate-limiting primitive and handles only {@code EINTR}
 * will treat {@code EPERM} as an unexpected error. If the only response to an unexpected error is
 * to retry immediately, the result is a CPU-bound busy-loop that can saturate a core and trigger
 * OOMKiller intervention or eviction by the container orchestrator.
 *
 * <p>Sibling annotations: {@link ChaosNanosleepEinval} targets invalid sleep durations; {@link
 * ChaosNanosleepEintr} targets the far more common signal-interruption case.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosNanosleepEperm(probability = 0.01)
 * class NanosleepEpermTest {
 *   @Test
 *   void schedulerFallsBackToLowerPrecisionSleepOnPermissionDenied(ConnectionInfo info) {
 *     // assert that the scheduler does not busy-spin and degrades gracefully
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosNanosleepEinval
 * @see ChaosNanosleepEintr
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosNanosleepEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.NANOSLEEP, errno = TimeErrno.EPERM)
public @interface ChaosNanosleepEperm {

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
   * @ChaosNanosleepEperm(id = "primary",  probability = 0.001)
   * @ChaosNanosleepEperm(id = "replica",  probability = 0.01)
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
    ChaosNanosleepEperm[] value();
  }
}
