/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

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
 * Injects {@code ESRCH} ("No such process") into every process-management syscall intercepted by
 * libchaos-process — {@code waitpid}, {@code fork}, {@code execve}, {@code posix_spawn}, {@code
 * pthread_create}, and their variants — simultaneously, gated by {@link #probability}, modelling
 * stale-pid conditions and process-group dissolution that can affect all process lifecycle
 * operations.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ESRCH}) tuple.
 * The {@code WILDCARD} selector intercepts every process-management syscall family simultaneously:
 * fork, execve, execveat, posix_spawn, posix_spawnp, pthread_create, and waitpid. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.
 *   <li>On each intercepted syscall, a Bernoulli trial with probability {@link #probability} runs.
 *   <li>When the trial fires, the interposer sets {@code errno = ESRCH} and returns {@code -1} (or
 *       the errno value directly for pthread_create and POSIX spawn functions) before the real
 *       kernel call executes.
 *   <li>The calling code receives: {@code waitpid()} returns {@code -1} with {@code errno = ESRCH}
 *       (3); {@code pthread_create}/{@code posix_spawn} return {@code ESRCH} directly; {@code
 *       strerror(ESRCH)}: "No such process".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code waitpid()} returns {@code -1} with {@code errno = ESRCH}; assert that the
 *       application treats ESRCH as a non-retryable diagnostic signal indicating a stale pid or
 *       dissolved process group — the process or group does not exist and will not reappear; assert
 *       that the stale pid is logged and removed from the application's child-tracking registry.
 *   <li>{@code waitpid(-pgid, ...)} returning ESRCH means the process group no longer exists;
 *       assert that the application detects this group dissolution and cleans up the group's
 *       registry entry without retrying — a dissolved group cannot be restored by waiting.
 *   <li>ESRCH from fork/pthread_create/posix_spawn (via the wildcard) is unexpected in normal
 *       operation; assert that the application's catch-all error handler logs the errno and the
 *       specific syscall name — ESRCH from these paths indicates a wildcard rule is active and
 *       should be surfaced for diagnostic purposes.
 *   <li>Assert that ESRCH is distinguished from ECHILD: ESRCH means the process or group does not
 *       exist at all; ECHILD means the process exists but is not a child; the cleanup action
 *       differs — ESRCH requires removing the stale pid from the registry, ECHILD may indicate a
 *       double-wait bug requiring investigation of the wait logic.
 * </ul>
 *
 * Production failure mode: a process group manager tracks child process groups in a registry; after
 * all members of a group exit the group dissolves; subsequent group waits return ESRCH; the manager
 * treats ESRCH as a transient error (confusing it with EINTR) and retries in a loop; the loop
 * consumes CPU while the registry entry remains stale, preventing new groups from being created
 * with the same pgid until the manager is restarted.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ESRCH} from {@code waitpid} has two distinct semantics depending on the pid argument:
 * for a specific positive pid, ESRCH means the process does not exist at all (not a child, not in
 * the system); for a negative pgid ({@code waitpid(-pgid, ...)}), ESRCH means no process in that
 * process group is a child of the caller, or the group itself does not exist. Both cases are
 * non-retryable — the process or group is gone. ESRCH is distinct from ECHILD: ECHILD fires when a
 * process exists but is not a child of the caller; ESRCH fires when the process or group does not
 * exist at all (for positive pids, the distinction depends on kernel version).
 *
 * <p>The wildcard selector fires ESRCH across all process-management families. ESRCH from
 * fork/posix_spawn/pthread_create is unusual (these normally produce EAGAIN, ENOMEM, or EPERM), but
 * the wildcard variant fires it to test whether the application's catch-all error handler correctly
 * identifies and logs ESRCH rather than conflating it with other errnos. This reveals missing or
 * incomplete errno classification in generic process-management error handling.
 *
 * <p>PID recycling produces apparent ESRCH: a child exits and is reaped; the kernel recycles its
 * pid to a new unrelated process; a late waitpid on the old pid receives ESRCH (the new process is
 * not a child); applications that track child pids across asynchronous operations must validate
 * that a stale pid refers to the correct process before interpreting ESRCH — using a generation
 * counter or storing the child's command name for comparison.
 *
 * <p>Compared with ECHILD (which can be the expected loop-termination condition for waitpid drain
 * loops), ESRCH is never an expected condition — it always indicates a pid management bug (stale
 * pid, double-wait, pid recycling) or group dissolution. Applications that have a single handler
 * for "waitpid returned -1" without distinguishing ESRCH from ECHILD will silently miss stale-pid
 * conditions that should trigger alerts.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEsrch(probability = 0.002)
 * class StalePidHandlingTest {
 *   @Test
 *   void processManagerLogsStalePidAndCleansRegistryOnEsrch(ConnectionInfo info) {
 *     // drive workload triggering waitpid and group waits; assert ESRCH treated as non-retryable;
 *     // assert stale pid logged and removed from registry; assert ESRCH vs ECHILD classified correctly;
 *     // assert group dissolution triggers registry cleanup
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3; ESRCH from waitpid is non-retryable so
 * even low rates produce registry cleanups — start with 1e-3 to validate diagnostic logging without
 * causing excessive registry churn.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEsrch.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ESRCH)
public @interface ChaosWildcardEsrch {

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
   * @ChaosWildcardEsrch(id = "primary",  probability = 0.001)
   * @ChaosWildcardEsrch(id = "replica",  probability = 0.01)
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
    ChaosWildcardEsrch[] value();
  }
}
