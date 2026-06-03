/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Injects {@code EINTR} failures on {@code nanosleep()} at a configurable probability,
 * simulating signal delivery that interrupts the sleep syscall. On Linux, {@code nanosleep}
 * returns {@code -1} with {@code errno = EINTR} when a signal is received during the sleep;
 * POSIX requires callers to check the remaining time and restart. This scenario exercises whether
 * the application's sleep loop correctly handles the interrupted case.
 *
 * <h2>How it is created</h2>
 *
 * <p>Applies one libchaos-time rule: {@code nanosleep:ERRNO:EINTR@<toxicity>}. The probability
 * is set to {@link #toxicity} — at the default 0.4, roughly 40 % of {@code nanosleep} calls
 * return {@code EINTR} instead of completing. The remaining 60 % succeed normally, so the
 * application is not completely starved of sleep — it must correctly retry the interrupted ones.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Mild</strong><br>
 * Applications that properly handle {@code EINTR} (restart the sleep, subtract elapsed time)
 * experience only a minor throughput reduction. Applications that treat {@code EINTR} as a fatal
 * error or silently ignore it (missing the retry) exhibit busy-wait loops consuming 100 % CPU,
 * dropped retry budgets, or incorrect backoff calculations. In production this failure mode
 * occurs when an async-signal-safe signal handler (SIGTERM, SIGHUP, SIGUSR1) is delivered to a
 * thread blocked in {@code nanosleep}.
 *
 * <h2>Industry references</h2>
 *
 * <p>The POSIX specification for {@code nanosleep} (IEEE Std 1003.1) mandates {@code EINTR}
 * handling and remaining-time tracking. The Linux man page {@code nanosleep(2)} §NOTES explicitly
 * warns that applications must loop on {@code EINTR}. Java's {@code Thread.sleep} is implemented
 * via {@code nanosleep} on Linux; native code calling {@code nanosleep} directly must handle this.
 * The "Fallacies of Distributed Computing" list implicitly covers signal handling as an
 * out-of-band communication channel that callers must anticipate.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @CompositeChaosNanosleepInterruption(toxicity = 0.5)
 * class NanosleepInterruptTest {
 *
 *   @Test
 *   void retryLoopDoesNotBusyWaitOnEintr(ConnectionInfo info) {
 *     // assert CPU usage stays bounded when nanosleep is frequently interrupted
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosNanosleepInterruption.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.time.testpack.composers.NanosleepInterruptionComposer",
    severity = Severity.MILD)
public @interface CompositeChaosNanosleepInterruption {

  /**
   * Probability that a given {@code nanosleep()} call returns {@code EINTR} instead of
   * completing. Must be in {@code (0.0, 1.0]}. Defaults to {@code 0.4} (40 % interruption rate).
   *
   * @return injection probability; higher values produce more frequent interruptions
   */
  double toxicity() default 0.4;

  /**
   * Container id to target. Empty string (the default) applies the scenario to every container
   * prepared with libchaos-time.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosNanosleepInterruption[] value();
  }
}
