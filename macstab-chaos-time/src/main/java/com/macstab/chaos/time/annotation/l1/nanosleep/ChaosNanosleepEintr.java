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
 * Injects {@code EINTR} into {@code nanosleep(2)}, causing the call to return {@code -1} with
 * {@code errno = EINTR} as if a signal interrupted the sleep before the requested duration elapsed.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code NANOSLEEP}, errno = {@code
 * EINTR}) tuple. The tuple is safe by construction — {@code EINTR} is the primary documented POSIX
 * result of {@code nanosleep(2)}: when a signal is delivered to the thread during the sleep, the
 * call returns immediately with {@code EINTR} and writes the remaining sleep time into the {@code
 * rem} argument. No runtime selector-errno validation is needed.
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EINTR}
 *       without waiting — the application's sleep is cut short with a partial-sleep indication.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep returns immediately with {@code EINTR}; correct code reads the remaining time
 *       from the {@code rem} argument and restarts the sleep, iterating until the full duration has
 *       elapsed.
 *   <li>Code that treats any {@code nanosleep} failure as fatal will exit event loops prematurely,
 *       causing reconnect storms or missed heartbeats.
 *   <li>Retry loops with a hard maximum iteration count may underslept significantly if many {@code
 *       EINTR} signals arrive during a single intended sleep interval.
 *   <li>Assert that the cumulative sleep duration is at least as long as the requested interval and
 *       that no connection or heartbeat is prematurely abandoned.
 * </ul>
 *
 * <p>In production, {@code EINTR} from {@code nanosleep} is a common production failure mode: any
 * process that registers signal handlers with {@code SA_RESTART} absent will receive {@code EINTR}
 * whenever a signal fires during a sleep — monitoring agents, GC safepoints, and JVM signal
 * handlers all trigger this on busy systems.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX requires that when a signal interrupts {@code nanosleep}, the call writes the remaining
 * sleep duration (the time not yet slept) to the {@code rem} argument if the pointer is non-null.
 * The canonical restart idiom is: {@code while (nanosleep(&req, &req) == -1 && errno == EINTR) {}}.
 * Failing to implement this idiom results in sleeping less than intended, which is a latent bug in
 * any back-pressure or rate-limiting loop.
 *
 * <p>The glibc wrapper for {@code nanosleep} does not restart automatically because the semantic of
 * a partial sleep is meaningful (the application may want to handle the signal first). This
 * contrasts with most other syscalls where glibc sets {@code SA_RESTART}.
 *
 * <p>{@code libchaos-time.so} injects {@code EINTR} at the C library wrapper level; the {@code rem}
 * struct is zeroed by the interposer (simulating a fully interrupted sleep) to trigger the worst
 * case for incorrectly written restart loops.
 *
 * <p>Sibling annotations: {@link com.macstab.chaos.time.annotation.l1.usleep.ChaosUsleepEintr} applies the same injection to the obsolete
 * {@code usleep(3)} wrapper; {@link ChaosNanosleepLatency} adds delay without cutting the sleep
 * short, useful for testing timeout over-runs.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosNanosleepEintr(probability = 0.1)
 * class NanosleepEintrTest {
 *   @Test
 *   void rateLimiterSleepsFullDurationDespiteSignalInterruptions(ConnectionInfo info) {
 *     // assert that the cumulative sleep interval matches the requested duration
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.time.annotation.l1.usleep.ChaosUsleepEintr
 * @see ChaosNanosleepLatency
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosNanosleepEintr.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.NANOSLEEP, errno = TimeErrno.EINTR)
public @interface ChaosNanosleepEintr {

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
   * @ChaosNanosleepEintr(id = "primary",  probability = 0.001)
   * @ChaosNanosleepEintr(id = "replica",  probability = 0.01)
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
    ChaosNanosleepEintr[] value();
  }
}
