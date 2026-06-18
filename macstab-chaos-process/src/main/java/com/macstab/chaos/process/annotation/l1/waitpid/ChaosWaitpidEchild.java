/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.waitpid;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessErrnoBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Injects {@code ECHILD} into {@code waitpid} calls intercepted by libchaos-process, causing the
 * calling code to observe a no-child-processes failure when attempting to reap a child process.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WAITPID}, errno = {@code ECHILD}) tuple.
 * The {@code WAITPID} selector intercepts {@code waitpid} calls only, leaving {@code fork}, {@code
 * posix_spawn}, and all other process syscalls unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code waitpid} wrapper at the dynamic-linker level.
 *   <li>On each {@code waitpid} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer returns {@code -1} and sets {@code errno = ECHILD}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: return value {@code -1}, {@code errno = ECHILD} (10), {@code
 *       strerror(ECHILD)}: "No child processes"; the specified pid does not exist as a child of the
 *       calling process, or the child has already been waited for and its zombie entry has been
 *       removed.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code waitpid} returns {@code -1} with {@code errno == ECHILD}; assert that the
 *       application handles ECHILD without treating it as an unexpected error — ECHILD is the
 *       normal result when using {@code waitpid(-1, ...)} to reap all children in a SIGCHLD handler
 *       (the loop terminates when ECHILD is returned, indicating no more children to reap).
 *   <li>Applications that call {@code waitpid} on a specific pid may receive ECHILD if the child
 *       has already been reaped by a concurrent SIGCHLD handler or a double-wait bug — assert that
 *       the application does not treat ECHILD from a specific-pid waitpid as a fatal error and
 *       instead logs the pid for diagnostic purposes.
 *   <li>Assert that the application does not enter an infinite retry loop on ECHILD — unlike EINTR
 *       (which warrants retry), ECHILD indicates the child is gone and retrying waitpid for the
 *       same pid will produce ECHILD on every subsequent attempt.
 * </ul>
 *
 * Production failure mode: a process supervisor uses a SIGCHLD handler that calls {@code
 * waitpid(-1, WNOHANG)} in a loop; the loop does not terminate on ECHILD and retries indefinitely;
 * the SIGCHLD handler consumes 100% CPU after all children have been reaped; the supervisor appears
 * stuck and does not accept new process launch requests.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ECHILD} from {@code waitpid} has two distinct sources: (1) the pid argument does not
 * refer to a child of the calling process — either the pid was never a child, or the child has
 * already been reaped and its zombie entry removed; (2) when pid is -1 (wait for any child), ECHILD
 * is returned when there are no children at all. The two cases have different semantic meaning:
 * pid=-1 ECHILD is a normal loop-termination condition; specific-pid ECHILD is a diagnostic signal
 * for a double-wait or pid-reuse bug.
 *
 * <p>waitpid returns -1 on error and sets errno (unlike POSIX spawn which returns the error code
 * directly). Code that tests {@code if (ret == -1 && errno == ECHILD)} is correct. A common bug is
 * checking only {@code if (ret < 0)} without also checking {@code errno == ECHILD}, which conflates
 * ECHILD (no-child, expected in loops) with EINVAL (invalid options, programming error) and ESRCH
 * (stale pid, diagnostic signal).
 *
 * <p>SIGCHLD handler design affects ECHILD likelihood: a handler that calls {@code waitpid(-1,
 * &status, WNOHANG)} in a loop until ECHILD must terminate on ECHILD without error logging; the
 * ECHILD return is the correct and expected loop-exit condition. Applications that log ECHILD at
 * ERROR level in SIGCHLD handlers produce false-positive alerts on every signal delivery after the
 * last child exits.
 *
 * <p>PID recycling is a related concern: a child exits, is reaped, and the kernel recycles its pid
 * to a new process; a subsequent waitpid on the old pid receives ECHILD (the new process is not a
 * child of the caller); if the application did not notice the child exit and retries waitpid, it
 * will receive ECHILD and must not interpret this as the new process exiting.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWaitpidEchild(probability = 0.01)
 * class WaitpidChildTerminationTest {
 *   @Test
 *   void sigchlHandlerTerminatesReapLoopOnEchildAndDoesNotLogAsError(ConnectionInfo info) {
 *     // verify ECHILD terminates waitpid(-1) loop; ECHILD not logged at ERROR;
 *     // specific-pid ECHILD logged at WARN with pid; no infinite retry on ECHILD
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; ECHILD occurs normally in waitpid loops;
 * any non-zero probability exercises the ECHILD loop-termination path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWaitpidEchild.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WAITPID, errno = ProcessErrno.ECHILD)
public @interface ChaosWaitpidEchild {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

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
   * @ChaosWaitpidEchild(id = "primary",  probability = 0.001)
   * @ChaosWaitpidEchild(id = "replica",  probability = 0.01)
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
    ChaosWaitpidEchild[] value();
  }
}
