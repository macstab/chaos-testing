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
 * Injects {@code EINTR} into {@code clock_gettime(2)}, causing the call to return {@code -1} with
 * {@code errno = EINTR} as if a signal was delivered to the thread while the syscall was in
 * progress.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code CLOCK_GETTIME}, errno =
 * {@code EINTR}) tuple. The tuple is safe by construction — {@code EINTR} is a documented POSIX
 * result indicating that a signal interrupted an otherwise synchronous call. No runtime
 * selector-errno validation is needed.
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EINTR}
 *       without invoking the real kernel call — the application sees a signal-interrupted failure.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code clock_gettime} returns {@code -1}; the standard response to {@code EINTR} is to
 *       retry the call immediately in a loop — code that does not loop will lose the sample.
 *   <li>Instrumentation loops in profiling agents and APM agents that timestamp every sample are
 *       the highest-frequency callers and the most likely to expose retry gaps.
 *   <li>Code that falls through on any negative return without checking errno will treat
 *       {@code EINTR} as a hard failure, which is incorrect.
 *   <li>Assert that the retry loop is bounded (avoids busy-spin under sustained signal pressure)
 *       and that the final timestamp is valid.
 * </ul>
 *
 * <p>In production, {@code EINTR} from {@code clock_gettime} is uncommon because the call is
 * synchronous and very fast; signal delivery during such a brief window is rare. It becomes
 * relevant in high-throughput signal environments (e.g. processes receiving many {@code SIGCHLD}
 * or {@code SIGALRM} signals per second).
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The Linux kernel restarts {@code clock_gettime} automatically for most signal dispositions
 * when the {@code SA_RESTART} flag is set. Without {@code SA_RESTART}, the syscall returns
 * {@code EINTR} to userspace. glibc's signal installation wrappers default to setting
 * {@code SA_RESTART}, which means that under normal conditions {@code EINTR} is suppressed.
 * The injection via {@code libchaos-time.so} bypasses this restart logic by returning
 * {@code EINTR} at the C library level rather than from the kernel.
 *
 * <p>This distinction matters: the injected {@code EINTR} is seen by application code, not by the
 * glibc restart machinery. It therefore exercises paths that only fire when {@code SA_RESTART} is
 * absent, providing coverage that is otherwise extremely difficult to trigger in integration tests.
 *
 * <p>Sibling annotations: {@link ChaosClockGettimeEagain} injects a similar transient failure;
 * {@link ChaosNanosleepEintr} and {@link ChaosUsleepEintr} apply the same injection to the sleep
 * syscalls where {@code EINTR} is far more common and carries semantic meaning (partial sleep).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosClockGettimeEintr(probability = 1e-3)
 * class ClockGettimeEintrTest {
 *   @Test
 *   void timestampLoopRetriesOnSignalInterruption(ConnectionInfo info) {
 *     // assert that the instrumentation loop retries and produces a valid timestamp
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosClockGettimeEagain
 * @see ChaosNanosleepEintr
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosClockGettimeEintr.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.CLOCK_GETTIME, errno = TimeErrno.EINTR)
public @interface ChaosClockGettimeEintr {

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
   * @ChaosClockGettimeEintr(id = "primary",  probability = 0.001)
   * @ChaosClockGettimeEintr(id = "replica",  probability = 0.01)
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
    ChaosClockGettimeEintr[] value();
  }
}
