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
 * After {@link #successesBeforeFailure} successful {@code waitpid} calls, injects {@code ECHILD} on
 * every subsequent call, modelling the scenario where all children have been reaped and subsequent
 * waits find no eligible children.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WAITPID}, errno = {@code ECHILD}, effect =
 * FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed, then the
 * counter trips permanently and every subsequent call returns the error code until the rule is
 * removed. Compile-time safety: invalid selector/errno/effect combinations have no annotation
 * class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code waitpid} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code waitpid} call
 *       returns {@code -1} with {@code errno = ECHILD}.
 *   <li>The calling code receives: return value {@code -1}, {@code errno = ECHILD} (10); the
 *       process has no more children to wait for.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally, returning valid child
 *       pids and status; all subsequent calls return ECHILD; assert that the application's
 *       child-reaping loop terminates correctly on ECHILD — this is the normal exit condition for
 *       {@code waitpid(-1, WNOHANG)} loops after all children have been reaped.
 *   <li>FAIL_AFTER models the all-children-reaped threshold: N children were spawned; N successful
 *       waits occur; the (N+1)th wait finds no children and returns ECHILD — assert that the
 *       application's loop terminates without treating ECHILD as an error after the expected N
 *       children have been reaped.
 *   <li>Assert that the application does not log ECHILD at ERROR level when it is the expected
 *       loop-termination condition; assert that it does log ECHILD at WARN level when ECHILD occurs
 *       before the expected number of children have been reaped (which may indicate a double-wait
 *       bug or premature child termination).
 * </ul>
 *
 * Production failure mode: a batch job spawner creates N worker processes and uses a wait loop to
 * collect their exit codes; the FAIL_AFTER threshold models the scenario where all N workers have
 * exited; the spawner's loop continues after ECHILD trying to collect more results, consuming
 * resources; the loop does not terminate because the ECHILD condition is not checked.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the all-children-reaped scenario: N children are spawned; N successful waits
 * collect their exit codes; the (N+1)th wait returns ECHILD because there are no more children.
 * This is the correct and expected behavior for a complete batch reaping loop. The FAIL_AFTER
 * effect forces a deterministic transition from the success phase (N reaps) to the ECHILD phase,
 * enabling sequential testing of the reaping-complete-and-loop-exits scenario.
 *
 * <p>waitpid returns -1 on error and sets errno. Code that tests {@code if (ret == -1 && errno ==
 * ECHILD)} is correct for detecting loop termination. The counter does not reset between test
 * methods when the annotation is at class scope, enabling sequential testing: the first test method
 * exercises N successful reaps; subsequent test methods exercise the ECHILD-loop-termination path.
 * Set {@link #successesBeforeFailure} to the expected number of children to be reaped before the
 * ECHILD termination condition should occur.
 *
 * <p>A key correctness invariant: if {@link #successesBeforeFailure} is N, the spawner must have
 * spawned exactly N children before the ECHILD occurs. If the application spawns M children but
 * ECHILD occurs after N {@literal <} M waits, the remaining M-N children are orphaned zombies.
 * Tests using this annotation should verify that the application detects and alerts on premature
 * ECHILD (fewer successful waits than spawned children) vs expected ECHILD (all children reaped).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWaitpidEchildFailAfter(successesBeforeFailure = 10)
 * class WaitpidAllChildrenReapedTest {
 *   @Test
 *   void batchSpawnerTerminatesReapLoopOnEchildAfterAllWorkersReaped(ConnectionInfo info) {
 *     // first 10 waits succeed; subsequent waits return ECHILD;
 *     // verify loop terminates; ECHILD not logged as error; all worker exit codes collected
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of children to be reaped; values 1–100 cover typical batch job sizes; 0 means no children
 * exist from the first wait (batch was never started).
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWaitpidEchildFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WAITPID, errno = ProcessErrno.ECHILD)
public @interface ChaosWaitpidEchildFailAfter {

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
   * @ChaosWaitpidEchildFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWaitpidEchildFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWaitpidEchildFailAfter[] value();
  }
}
