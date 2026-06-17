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
 * Injects {@code EINTR} into every interposed time syscall ({@code clock_gettime}, {@code
 * nanosleep}, {@code usleep}), causing each to return {@code -1} with {@code errno = EINTR} as if a
 * signal interrupted the call.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code WILDCARD}, errno = {@code
 * EINTR}) tuple. The {@code WILDCARD} selector matches all three interposed time syscalls
 * simultaneously — equivalent to applying {@link
 * com.macstab.chaos.time.annotation.l1.clock_gettime.ChaosClockGettimeEintr}, {@link
 * com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepEintr}, and {@link
 * com.macstab.chaos.time.annotation.l1.usleep.ChaosUsleepEintr} in a single annotation. No runtime
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
 *   <li>On every intercepted call to any of the three syscalls, a Bernoulli trial with probability
 *       {@link #probability} is conducted independently.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EINTR}
 *       without performing any real work.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>All three time-related syscalls may return {@code EINTR} at any time; applications must
 *       handle the error for all three call sites, not just {@code nanosleep}.
 *   <li>Cascading failures are possible: a {@code clock_gettime} {@code EINTR} causes a missed
 *       timestamp sample, while a concurrent {@code nanosleep} {@code EINTR} cuts the back-off
 *       short, producing a retry storm.
 *   <li>This annotation is most powerful for testing the signal-safety posture of the entire
 *       time-related call graph of the application under test.
 *   <li>Assert that the application is stable and produces correct outputs despite continuous
 *       {@code EINTR} injection across all time syscalls.
 * </ul>
 *
 * <p>In production, simultaneous {@code EINTR} across all time calls occurs when the process is
 * subjected to a high-frequency signal (e.g. profiler, GC safepoint, or {@code SIGCHLD} storm).
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code WILDCARD} selector is the most aggressive time-chaos primitive: it exercises the
 * union of all signal-interruption paths with a single annotation. This is particularly useful for
 * fuzz-testing the time subsystem of applications that were written assuming {@code clock_gettime}
 * never fails and {@code nanosleep} only fails with {@code EINTR}.
 *
 * <p>The injection probability is applied independently to each call; a probability of 0.01 means
 * roughly 1 in 100 calls of each type is interrupted. Under high-frequency time calls (e.g. a hot
 * loop that reads the clock for every cache entry), this creates a visible stream of errors that
 * stress-tests the retry and error-handling logic thoroughly.
 *
 * <p>Sibling per-syscall annotations ({@link
 * com.macstab.chaos.time.annotation.l1.clock_gettime.ChaosClockGettimeEintr}, {@link
 * com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepEintr}, {@link
 * com.macstab.chaos.time.annotation.l1.usleep.ChaosUsleepEintr}) allow scoped injection to a single
 * syscall when a targeted test is needed. Use the wildcard form when testing system-wide signal
 * resilience.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosWildcardEintr(probability = 0.01)
 * class WildcardEintrTest {
 *   @Test
 *   void applicationRemainsStableUnderContinuousSignalInterruption(ConnectionInfo info) {
 *     // assert that the application produces correct outputs under EINTR injection
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.time.annotation.l1.clock_gettime.ChaosClockGettimeEintr
 * @see com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepEintr
 * @see com.macstab.chaos.time.annotation.l1.usleep.ChaosUsleepEintr
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosWildcardEintr.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.WILDCARD, errno = TimeErrno.EINTR)
public @interface ChaosWildcardEintr {

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
   * @ChaosWildcardEintr(id = "primary",  probability = 0.001)
   * @ChaosWildcardEintr(id = "replica",  probability = 0.01)
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
    ChaosWildcardEintr[] value();
  }
}
