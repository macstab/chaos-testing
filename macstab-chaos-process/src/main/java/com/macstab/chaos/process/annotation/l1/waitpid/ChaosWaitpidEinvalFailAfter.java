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
 * After {@link #successesBeforeFailure} successful {@code waitpid} calls, injects {@code EINVAL}
 * on every subsequent call, modelling a waitpid options regression where a configuration change
 * introduces an invalid flag after N successful waits.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WAITPID}, errno = {@code EINVAL},
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
 *       call returns {@code -1} with {@code errno = EINVAL}.</li>
 *   <li>The calling code receives: return value {@code -1}, {@code errno = EINVAL} (22); the
 *       options bitmask contains an invalid flag combination.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return EINVAL; assert that the application treats EINVAL as a non-retryable programming
 *       error and logs the options bitmask value for diagnostic purposes — EINVAL from waitpid
 *       never resolves by retrying with the same options value.</li>
 *   <li>FAIL_AFTER models an options regression: N waits succeed with a valid options value;
 *       a hot-reload changes the options mask to include an invalid flag; all subsequent waits
 *       return EINVAL — assert that the application detects this regression and alerts with the
 *       invalid options value.</li>
 *   <li>Assert that children spawned during the EINVAL phase become permanent zombies (unreaped);
 *       the application must detect this zombie accumulation and alert before the process table
 *       fills.</li>
 * </ul>
 * Production failure mode: a process manager constructs the waitpid options mask from a
 * configuration file to support optional job-control semantics; a configuration push changes
 * the WUNTRACED flag encoding, producing a bitmask with invalid bits; after N successful waits
 * all subsequent waits return EINVAL; children accumulate as zombies; the manager does not log
 * the options value or detect zombie accumulation.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER models the options regression: N waits succeed; a configuration change corrupts
 * the options mask; all subsequent waits fail with EINVAL. Real EINVAL from waitpid fires
 * deterministically whenever the invalid options are used and persists until the options are
 * corrected. waitpid returns -1 and sets errno; checking {@code if (ret == -1 && errno == EINVAL)}
 * is correct.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises N successful waits (pre-regression);
 * subsequent test methods exercise the EINVAL phase. Set {@link #successesBeforeFailure} to the
 * number of waits expected before the configuration change takes effect.
 *
 * <p>A critical consequence of sustained EINVAL from waitpid: children that exit after the EINVAL
 * phase begins accumulate as zombies because no successful waitpid will reap them. The zombie
 * accumulation rate equals the child spawn rate during the EINVAL phase. Applications must detect
 * EINVAL from waitpid and stop spawning new children until the options are corrected, to prevent
 * zombie accumulation from exhausting the process table.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWaitpidEinvalFailAfter(successesBeforeFailure = 20)
 * class WaitpidOptionsRegressionTest {
 *   @Test
 *   void processManagerAlertsOnEinvalLogsOptionsAndStopsSpawningToPreventZombies(ConnectionInfo info) {
 *     // first 20 waits succeed; subsequent waits return EINVAL;
 *     // verify options bitmask logged; no retry; spawning stopped; zombie alert raised
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * successful waits before the options regression takes effect; values 5–100 cover most hot-reload
 * scenarios; 0 means every wait is invalid from startup.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWaitpidEinvalFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WAITPID, errno = ProcessErrno.EINVAL)
public @interface ChaosWaitpidEinvalFailAfter {

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
   * @ChaosWaitpidEinvalFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWaitpidEinvalFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWaitpidEinvalFailAfter[] value();
  }
}
