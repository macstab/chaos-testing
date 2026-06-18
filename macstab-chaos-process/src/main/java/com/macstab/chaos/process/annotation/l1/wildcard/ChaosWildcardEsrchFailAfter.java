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
 * all intercepted families, injects {@code ESRCH} on every subsequent call, modelling a
 * process-group dissolution scenario where N group members are reaped and the group dissolves,
 * causing all subsequent process-management operations to report "No such process".
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ESRCH}, effect =
 * FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N intercepted
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
 *       with {@code errno = ESRCH}.
 *   <li>The calling code receives: {@code waitpid()} returns {@code -1} with {@code errno = ESRCH}
 *       (3); {@code posix_spawn}/{@code pthread_create} return {@code ESRCH} directly; {@code
 *       strerror(ESRCH)}: "No such process".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return ESRCH permanently; assert that the application treats ESRCH from
 *       waitpid as a non-retryable signal that the process or group has dissolved, cleans up the
 *       stale pid/pgid from its registry, and does not retry — the dissolved group cannot be
 *       restored by waiting.
 *   <li>FAIL_AFTER models the group dissolution threshold: N group members are reaped across N
 *       successful waitpid calls; the group dissolves; all subsequent group waits return ESRCH —
 *       assert that the application detects the group dissolution, removes the group's registry
 *       entry, and does not create a new group with the same pgid until the registry is cleaned.
 *   <li>Assert that ESRCH from non-waitpid call sites (via the wildcard — fork, spawn, thread
 *       create) is logged with the specific syscall name — ESRCH from these paths indicates a
 *       wildcard rule is active and should be surfaced for diagnostic purposes.
 * </ul>
 *
 * Production failure mode: a process group manager tracks child groups in a registry; after N
 * members of a group are reaped the group dissolves; subsequent group waits return ESRCH; the
 * manager treats ESRCH as EINTR and retries in a loop; the stale registry entry prevents new groups
 * from being created with the same pgid; the manager cannot start new work until restarted.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>ESRCH from waitpid has two sources: for positive pids, it means the pid does not exist at all
 * (not a child, not in the process table — distinct from ECHILD which fires when the pid exists but
 * is not a child); for negative pgids ({@code waitpid(-pgid, ...)}), it means the process group
 * does not exist or no process in it is a child of the caller. The FAIL_AFTER variant fires ESRCH
 * permanently after N calls, modeling the point at which a group dissolves after all members have
 * been reaped.
 *
 * <p>The WILDCARD counter charges across all process-management families. The ESRCH phase begins
 * when the combined call traffic (across fork, exec, spawn, pthread_create, waitpid) exhausts the
 * counter. Set {@link #successesBeforeFailure} to the total process-management call count during
 * the pre-dissolution phase — this includes all the fork and spawn calls that created the group
 * members, plus the N waitpid calls that reaped them.
 *
 * <p>The counter does not reset between test methods at class scope. First test method: N
 * successful calls (group creation and member reaping). Subsequent test methods: ESRCH phase (group
 * dissolved, all waits fail). The registry cleanup path must be tested in the subsequent test
 * method to verify that the stale group entry is correctly removed.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEsrchFailAfter(successesBeforeFailure = 70)
 * class GroupDissolutionTest {
 *   @Test
 *   void processGroupManagerCleansRegistryAndDoesNotRetryOnEsrch(ConnectionInfo info) {
 *     // first 70 process calls succeed; subsequent calls return ESRCH;
 *     // verify stale pid/pgid logged; verify registry cleaned; verify no retry;
 *     // verify ESRCH vs ECHILD classified correctly; verify new group can be created after cleanup
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the total
 * process-management call count during group creation and member reaping; for a group of K members,
 * the minimum threshold is K (spawns) + K (waitpid calls to reap) + other process-management calls;
 * values 10–300 cover typical process-group lifecycle scenarios.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEsrchFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ESRCH)
public @interface ChaosWildcardEsrchFailAfter {

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
   * @ChaosWildcardEsrchFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEsrchFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEsrchFailAfter[] value();
  }
}
