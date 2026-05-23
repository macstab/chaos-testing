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
 * After {@link #successesBeforeFailure} successful {@code waitpid} calls, injects {@code ESRCH}
 * on every subsequent call, modelling the scenario where a process group dissolves after N
 * successful waits and all subsequent group waits find a non-existent process group.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WAITPID}, errno = {@code ESRCH},
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
 *       call returns {@code -1} with {@code errno = ESRCH}.</li>
 *   <li>The calling code receives: return value {@code -1}, {@code errno = ESRCH} (3); the
 *       process or process group being waited for does not exist as a child of the caller.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return ESRCH; assert that the application treats ESRCH as a diagnostic signal indicating
 *       that the pid being tracked has become stale — the process or group has already been
 *       reaped by another waiter or has exited before the call.</li>
 *   <li>FAIL_AFTER models the process-group-dissolution scenario: N children are reaped from a
 *       process group; the (N+1)th wait attempts to reap from the same group; the group has
 *       dissolved (all members exited and reaped); subsequent group waits return ESRCH — assert
 *       that the application detects this dissolution and cleans up the group's registry entry.</li>
 *   <li>Assert that the application does not retry on ESRCH — the process/group does not exist
 *       and will not reappear; assert that the stale pid or pgid is logged for diagnostic
 *       purposes so operators can trace the premature dissolution.</li>
 * </ul>
 * Production failure mode: a process pool reaps workers using a group waitpid loop; the pool
 * tracks the process group id in a registry; after N workers are reaped the group dissolves;
 * subsequent waits return ESRCH; the pool does not clean the registry entry and continues
 * waiting for the dissolved group; the registry entry remains stale indefinitely, preventing
 * the pool from creating a new group with the same id.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER models the process-group-dissolution threshold: N group members are reaped; after
 * N successful waits all members have exited and their zombies have been collected; the group
 * dissolves; subsequent group waits return ESRCH. Real ESRCH from this source is deterministic —
 * it fires on the (N+1)th wait and persists. waitpid returns -1 and sets errno; checking
 * {@code if (ret == -1 && errno == ESRCH)} is correct.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises N successful group waits (the group
 * exists and has members); subsequent test methods exercise the ESRCH-group-dissolved phase.
 * Set {@link #successesBeforeFailure} to the number of group members expected to be reaped before
 * the group dissolves.
 *
 * <p>ESRCH from waitpid on a specific positive pid is distinct from ESRCH on a negative pgid.
 * For a specific pid, ESRCH means the process does not exist at all (it may have been reaped by
 * a concurrent waiter). For a pgid, ESRCH means no process in the group is a child of the caller.
 * Both cases warrant non-retryable diagnostic handling, but the log message should distinguish
 * between the two to aid operator investigation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWaitpidEsrchFailAfter(successesBeforeFailure = 8)
 * class WaitpidGroupDissolutionTest {
 *   @Test
 *   void processPoolCleansGroupRegistryOnEsrchAndLogsGroupId(ConnectionInfo info) {
 *     // first 8 group waits succeed; subsequent waits return ESRCH;
 *     // verify group registry cleaned; pgid logged; no retry; ESRCH vs ECHILD distinguished;
 *     // new group can be created after cleanup
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of group members that will be reaped before group dissolution; values 2–50 cover most
 * process group scenarios; 0 means the group does not exist from the first wait.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWaitpidEsrchFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WAITPID, errno = ProcessErrno.ESRCH)
public @interface ChaosWaitpidEsrchFailAfter {

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
   * @ChaosWaitpidEsrchFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWaitpidEsrchFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWaitpidEsrchFailAfter[] value();
  }
}
