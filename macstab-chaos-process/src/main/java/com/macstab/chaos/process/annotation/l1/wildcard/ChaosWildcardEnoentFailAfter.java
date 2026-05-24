/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

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
 * After {@link #successesBeforeFailure} successful process-management syscall invocations across
 * all intercepted families, injects {@code ENOENT} on every subsequent call, modelling a binary
 * disappearance scenario where a rolling deployment removes an executable after N successful
 * process launches, causing all subsequent process-management operations to report "No such file or
 * directory".
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ENOENT}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N intercepted
 * process-management calls (across all families — fork, execve, posix_spawn, pthread_create,
 * waitpid) succeed, then the counter trips permanently and every subsequent call returns the error
 * code until the rule is removed. Compile-time safety: invalid selector/errno/effect combinations
 * have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter shared across all intercepted syscall
 *       families; the counter does not reset automatically between test methods when the annotation
 *       is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent process-management
 *       call returns {@code -1} (or the errno value directly for pthread_create and posix_spawn)
 *       with {@code errno = ENOENT}.
 *   <li>The calling code receives: {@code execve()}/{@code fork()} return {@code -1} with {@code
 *       errno = ENOENT} (2); {@code posix_spawn}/{@code pthread_create} return {@code ENOENT}
 *       directly; {@code strerror(ENOENT)}: "No such file or directory".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return ENOENT permanently; assert that the application does not retry
 *       without verifying the binary path exists — ENOENT is non-retryable with the same path; the
 *       application must alert the deployment system that the binary is missing.
 *   <li>FAIL_AFTER models the rolling deployment scenario: N spawn calls succeed while the old
 *       binary is present; a deployment removes the old binary; all subsequent spawn calls return
 *       ENOENT — assert that the application logs the binary path and PATH value at the time of
 *       first ENOENT and sends a deployment alert to the CI/CD system.
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       ENOENT from a spawn call — the child was never created; assert that the child-tracking
 *       registry is not updated when the spawn fails with ENOENT.
 * </ul>
 *
 * Production failure mode: a supervisor runs N workers using posix_spawnp; a deployment removes the
 * old binary and installs a new one; during the removal window all spawn calls return ENOENT; the
 * supervisor retries immediately without checking if the binary exists, producing a tight loop; the
 * deployment window extends due to spawner load; the supervisor fills the log with ENOENT errors
 * without sending a deployment alert.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>ENOENT from process-management syscalls arises exclusively from the exec family and POSIX
 * spawn: the executable binary was not found at the specified path ({@code execve}/{@code
 * execveat}) or in any PATH directory ({@code posix_spawnp}). The FAIL_AFTER variant models the
 * deterministic transition: N launches succeed, then the binary disappears and all subsequent
 * launches fail.
 *
 * <p>The WILDCARD counter charges across all process-management families. The ENOENT phase begins
 * when the combined traffic exhausts the counter. Set {@link #successesBeforeFailure} to the total
 * number of process-management calls during the pre-removal phase — this is the total across all
 * families (fork + exec + spawn + pthread_create + waitpid), not just spawn calls.
 *
 * <p>The counter does not reset between test methods at class scope. First test method: N
 * successful calls (normal operation with binary present). Subsequent test methods: ENOENT phase
 * (binary removed, all spawn attempts fail). This enables sequential testing of the normal
 * operating phase and the deployment-window failure phase.
 *
 * <p>Non-retryable constraint: applications must not retry ENOENT from spawn with the same binary
 * path without first confirming the binary exists. A log-and-verify pattern is correct: log ENOENT
 * with the binary path and PATH, send a deployment alert, poll for binary existence with a
 * configurable interval, and resume spawning only after the binary reappears.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEnoentFailAfter(successesBeforeFailure = 30)
 * class BinaryDisappearanceTest {
 *   @Test
 *   void supervisorLogsPathAndSendsDeploymentAlertOnEnoentAndPollsForBinaryReturn(ConnectionInfo info) {
 *     // first 30 process calls succeed; subsequent calls return ENOENT;
 *     // verify binary path logged; verify deployment alert sent; verify no immediate retry;
 *     // verify poll-and-wait pattern implemented; verify no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the total number
 * of process-management calls during normal operation before the binary disappears; values 10–200
 * cover typical workload phases; 0 means the binary is absent from the first call.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEnoentFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ENOENT)
public @interface ChaosWildcardEnoentFailAfter {

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
   * @ChaosWildcardEnoentFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEnoentFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEnoentFailAfter[] value();
  }
}
