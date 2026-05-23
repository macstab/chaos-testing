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
 * Injects {@code EAGAIN} into {@code nanosleep(2)}, causing the call to return {@code -1} with
 * {@code errno = EAGAIN} as if a temporary resource constraint prevented the sleep from starting.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code NANOSLEEP}, errno = {@code EAGAIN})
 * tuple. The tuple is safe by construction — {@code EAGAIN} is a valid transient POSIX error
 * indicating resource temporarily unavailable; injecting it exercises defensive retry paths in
 * sleep-based back-pressure logic. No runtime selector-errno validation is needed.
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
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EAGAIN}
 *       without sleeping — the sleep is skipped.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Retry loops that treat {@code EAGAIN} as a signal to retry immediately will busy-spin if
 *       the injection rate is high, consuming CPU without actually waiting.
 *   <li>Back-pressure components that use {@code nanosleep} as a pacing primitive may produce
 *       bursts of activity rather than smooth throttling.
 *   <li>Assert that the application applies exponential backoff on repeated {@code EAGAIN} returns
 *       rather than immediately retrying in a tight loop.
 * </ul>
 *
 * <p>In production, {@code EAGAIN} from {@code nanosleep} does not occur on standard Linux kernels.
 * It is more relevant in POSIX emulation layers and exotic real-time operating systems. Injecting
 * it is primarily useful for verifying that callers handle any non-zero return from {@code nanosleep}
 * defensively, regardless of the specific errno.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Standard Linux kernels return only {@code EINTR} or {@code EINVAL} from {@code nanosleep};
 * {@code EAGAIN} is not a normal kernel response. Injecting it via {@code libchaos-time.so}
 * exercises application code paths that use generic {@code errno != EINTR} guards — a common
 * pattern in libraries that want to restart the sleep for any transient error.
 *
 * <p>Code that uses a pattern such as {@code while (nanosleep(...) && errno != EINVAL) {}} will
 * restart the sleep on {@code EAGAIN} without sleeping any additional time, potentially leading
 * to a tight loop. This annotation surfaces that bug in integration tests before it reaches
 * production.
 *
 * <p>Sibling annotations: {@link ChaosNanosleepEintr} is the far more realistic signal-interruption
 * case; {@link ChaosNanosleepLatency} increases sleep duration without error, useful for testing
 * timeout over-runs.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosNanosleepEagain(probability = 1e-3)
 * class NanosleepEagainTest {
 *   @Test
 *   void backpressureLoopDoesNotBusySpinOnTransientError(ConnectionInfo info) {
 *     // assert that CPU usage remains bounded even when nanosleep fails transiently
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosNanosleepEintr
 * @see ChaosNanosleepLatency
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosNanosleepEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.NANOSLEEP, errno = TimeErrno.EAGAIN)
public @interface ChaosNanosleepEagain {

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
   * @ChaosNanosleepEagain(id = "primary",  probability = 0.001)
   * @ChaosNanosleepEagain(id = "replica",  probability = 0.01)
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
    ChaosNanosleepEagain[] value();
  }
}
