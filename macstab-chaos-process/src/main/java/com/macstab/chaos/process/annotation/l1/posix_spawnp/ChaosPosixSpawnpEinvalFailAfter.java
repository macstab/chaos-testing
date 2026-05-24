/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawnp;

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
 * After {@link #successesBeforeFailure} successful {@code posix_spawnp} calls, injects {@code
 * EINVAL} on every subsequent call, causing the calling code to observe a persistent
 * invalid-argument failure that models a spawn attribute configuration regression.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code EINVAL},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawnp} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code posix_spawnp}
 *       call returns {@code EINVAL} directly (POSIX spawn returns the error code, not -1).
 *   <li>The calling code receives: return value {@code EINVAL} (22); no child process is created;
 *       the pid output parameter is not set to a valid value.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code EINVAL}; assert that the application treats EINVAL as a non-retryable
 *       programming error — spawn attribute or file-action structure contains an invalid value that
 *       must be fixed in code, not retried.
 *   <li>FAIL_AFTER models the attribute configuration regression: N successful spawns occur using a
 *       valid configuration; after N spawns a hot-reload or dynamic reconfiguration produces an
 *       invalid {@code posix_spawnattr_t} or {@code posix_spawn_file_actions_t}; subsequent spawns
 *       fail with EINVAL — assert that the application detects this transition and escalates with
 *       the invalid attribute values for operator debugging.
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       post-threshold EINVAL — POSIX does not define the pid value when spawn fails; also assert
 *       that no retry loop is applied to EINVAL failures.
 * </ul>
 *
 * Production failure mode: a command executor builds {@code posix_spawnattr_t} structures from a
 * serialised configuration; a configuration hot-reload changes the scheduling policy field to an
 * integer that is not a valid POSIX scheduling policy; after the reload all subsequent spawns
 * return EINVAL; the executor logs a generic "spawn failed (22)" without the attribute values,
 * requiring engineers to reproduce the configuration state to diagnose the regression.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the configuration-regression scenario: N spawns succeed with a valid
 * attribute structure; a configuration change corrupts the structure; subsequent spawns fail with
 * EINVAL. Real EINVAL from posix_spawnp is not probabilistic — it fires deterministically once the
 * invalid attribute is present and persists until the attribute is corrected. POSIX spawn returns
 * the error code directly — checking {@code if (ret < 0)} silently misses EINVAL (22).
 *
 * <p>Sources of EINVAL in posix_spawnp include: invalid scheduling policy in {@code
 * posix_spawnattr_t} (value not SCHED_OTHER, SCHED_FIFO, or SCHED_RR); scheduling parameter out of
 * range for the policy; invalid signal number in the signal mask; invalid fd number in {@code
 * posix_spawn_file_actions_t}; invalid flags in the file-action sequence. The {@code $PATH} search
 * that distinguishes spawnp from spawn does not itself produce EINVAL; EINVAL comes from the
 * attribute/action validation step that occurs after the binary is found.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises the success path (calls 1 through N
 * with the valid configuration); subsequent test methods exercise the EINVAL path automatically.
 * Set {@link #successesBeforeFailure} to the number of spawns expected between configuration
 * hot-reloads to model the exact moment the regression takes effect.
 *
 * <p>EINVAL is strictly non-retryable: it represents a programming error in the spawn attribute
 * setup, not a transient resource condition. Applications that retry on EINVAL will loop
 * indefinitely on the same invalid attribute structure. The diagnostic emitted on EINVAL should
 * include the scheduling policy integer, signal mask bitmask, and file-action sequence for the
 * failing spawn, enabling rapid identification of the invalid field after a configuration
 * regression.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEinvalFailAfter(successesBeforeFailure = 20)
 * class PosixSpawnpAttributeRegressionTest {
 *   @Test
 *   void executorLogsAttributeValuesOnEinvalAfterHotReloadAndDoesNotRetry(ConnectionInfo info) {
 *     // first 20 spawns succeed; subsequent spawns return EINVAL;
 *     // verify attribute values logged; no retry; no waitpid on uninit pid; alert escalated
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * spawns expected before the configuration regression takes effect; values 5–50 cover most
 * hot-reload scenarios; 0 means every spawn is invalid from the first call.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnpEinvalFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.EINVAL)
public @interface ChaosPosixSpawnpEinvalFailAfter {

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
   * @ChaosPosixSpawnpEinvalFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnpEinvalFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnpEinvalFailAfter[] value();
  }
}
