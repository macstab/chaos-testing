/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.waitpid;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessFailAfterBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * After {@link #successesBeforeFailure} successful {@code waitpid} calls, injects {@code EINTR}
 * on every subsequent call, modelling a sustained signal-storm scenario where the process receives
 * continuous signals that interrupt every subsequent wait.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WAITPID}, errno = {@code EINTR},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code waitpid} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code waitpid}
 *       call returns {@code -1} with {@code errno = EINTR}.</li>
 *   <li>The calling code receives: return value {@code -1}, {@code errno = EINTR} (4); the wait
 *       was interrupted by a signal on every attempt.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return EINTR permanently; assert that the application's EINTR retry loop is bounded —
 *       an unbounded retry loop on EINTR will spin indefinitely if signals are delivered
 *       continuously.</li>
 *   <li>FAIL_AFTER models a sustained signal storm: N waits succeed normally; a high-frequency
 *       signal source begins delivering signals; all subsequent waits return EINTR — assert that
 *       the application detects the sustained EINTR condition and either uses WNOHANG to avoid
 *       blocking, or escalates the sustained interruption after a retry budget is exhausted.</li>
 *   <li>Assert that the application checks its shutdown flag on each EINTR retry — a sustained
 *       EINTR triggered by SIGTERM should cause the application to exit the wait loop and
 *       proceed with graceful shutdown, not loop forever on EINTR.</li>
 * </ul>
 * Production failure mode: a process supervisor is in a blocking waitpid when a high-frequency
 * timer signal (SIGALRM or SIGRTMIN) is set up by a library; every waitpid attempt returns EINTR;
 * the supervisor's EINTR retry loop has no bound and no shutdown-flag check; the supervisor spins
 * at 100% CPU retrying waitpid indefinitely, unable to collect any child exit codes.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER models a sustained signal-storm where every waitpid attempt is interrupted. In
 * the transient EINTR case (ERRNO variant), the signal is occasional and the retry succeeds quickly.
 * FAIL_AFTER models the sustained case: a continuously firing signal source interrupts every wait;
 * the application must detect this and fall back to non-blocking waitpid (WNOHANG) or implement
 * a retry budget with circuit-breaker escalation.
 *
 * <p>waitpid returns -1 on error and sets errno. The EINTR retry pattern:
 * {@code do { ret = waitpid(pid, &status, 0); retries++; } while (ret == -1 && errno == EINTR && retries < MAX && !shutdown)}
 * is correct for bounded retry. The counter does not reset between test methods when the annotation
 * is at class scope. Set {@link #successesBeforeFailure} to the number of waits expected to succeed
 * before the signal storm begins; 0 means the storm begins immediately from startup.
 *
 * <p>Sustained EINTR from waitpid can arise from timer signals (SIGALRM, SIGRTMIN+n), periodic
 * health-check signals, or from signal handler chains that deliver multiple signals in rapid
 * succession. Applications that use libraries installing their own signal handlers (JVM, Go runtime,
 * Python GIL) may be especially susceptible to sustained EINTR from waitpid.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWaitpidEintrFailAfter(successesBeforeFailure = 5)
 * class WaitpidSignalStormTest {
 *   @Test
 *   void processManagerFallsBackToWnohangAfterSustainedEintrAndChecksShutdownFlag(ConnectionInfo info) {
 *     // first 5 waits succeed; subsequent waits return EINTR indefinitely;
 *     // verify bounded retry; fallback to WNOHANG; shutdown flag checked; no infinite spin
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * successful waits expected before the signal storm begins; values 1–50 cover most realistic
 * scenarios; 0 means the signal storm begins from the first wait.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWaitpidEintrFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WAITPID, errno = ProcessErrno.EINTR)
public @interface ChaosWaitpidEintrFailAfter {

  /**
   * @return number of matched calls allowed to succeed before failure begins ({@code >= 0})
   */
  long successesBeforeFailure() default 0L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-process
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosWaitpidEintrFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWaitpidEintrFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWaitpidEintrFailAfter[] value();
  }
}
