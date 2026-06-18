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
 * all intercepted families, injects {@code ECHILD} on every subsequent call, modelling an
 * all-children-reaped scenario where a process supervisor's child-tracking phase completes after N
 * operations and all subsequent waits find no eligible children.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code ECHILD}, effect
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
 *       with {@code errno = ECHILD}.
 *   <li>The calling code receives: {@code waitpid()} returns {@code -1} with {@code errno = ECHILD}
 *       (10); {@code posix_spawn}/{@code pthread_create} return {@code ECHILD} directly; {@code
 *       strerror(ECHILD)}: "No child processes".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return ECHILD permanently; for waitpid, assert that the application
 *       correctly terminates its child-reaping loop on ECHILD — ECHILD is the expected loop-exit
 *       condition for {@code waitpid(-1, WNOHANG)} drain loops after all children have been reaped.
 *   <li>FAIL_AFTER models the all-children-reaped threshold: N process-management operations create
 *       and reap N children; the (N+1)th wait returns ECHILD because there are no more children —
 *       assert that the application's reaping loop terminates correctly and that the child-tracking
 *       registry is updated to reflect that all children have been collected.
 *   <li>Assert that ECHILD from non-waitpid call sites (via the wildcard) is logged with the
 *       specific syscall name — ECHILD from fork or thread creation indicates a wildcard rule is
 *       active and should be surfaced for diagnostic purposes rather than silently swallowed.
 * </ul>
 *
 * Production failure mode: a batch job supervisor spawns N worker processes and uses a drain loop
 * to collect their exit codes; the FAIL_AFTER threshold equals N; after all workers are reaped the
 * supervisor's loop should terminate; if the loop does not check for ECHILD, it continues calling
 * waitpid indefinitely, accumulating CPU cycles and preventing the supervisor from processing the
 * next batch.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>ECHILD from waitpid is the normal and expected loop-exit condition for child-reaping drain
 * loops, not an error. The FAIL_AFTER variant models the deterministic transition: N children are
 * spawned and reaped; the reaping-complete ECHILD fires on the (N+1)th wait. The wildcard counter
 * charges across all process-management families, so the threshold must account for all fork,
 * posix_spawn, pthread_create, waitpid, and exec calls combined, not just waitpid calls.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. First
 * test method: N successful calls (spawning and reaping all children). Subsequent test methods:
 * ECHILD phase (drain loop should terminate correctly). Set {@link #successesBeforeFailure} to the
 * combined count of all process-management calls during the pre-ECHILD phase.
 *
 * <p>The distinction between expected ECHILD (all children reaped — FAIL_AFTER models this) and
 * unexpected ECHILD (fewer children reaped than spawned — indicates a double-wait bug) must be
 * tracked by the application. The application should count spawned vs reaped children; if ECHILD
 * fires before the reaped count equals the spawned count, it should alert on the discrepancy.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEchildFailAfter(successesBeforeFailure = 60)
 * class AllChildrenReapedTest {
 *   @Test
 *   void batchSupervisorTerminatesReapLoopAndUpdatesRegistryOnEchild(ConnectionInfo info) {
 *     // first 60 process calls succeed; subsequent calls return ECHILD;
 *     // verify drain loop terminates on ECHILD; verify registry updated; verify ECHILD not logged
 *     // as error when expected; verify ECHILD from non-waitpid paths logged with syscall name
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the total number
 * of process-management calls the application makes during its spawn-and-reap phase (spawning N
 * children contributes N + N waitpid calls at minimum); values 10–500 cover typical batch
 * workloads; 0 means ECHILD fires immediately on the first operation.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEchildFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ECHILD)
public @interface ChaosWildcardEchildFailAfter {

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
   * @ChaosWildcardEchildFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEchildFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEchildFailAfter[] value();
  }
}
