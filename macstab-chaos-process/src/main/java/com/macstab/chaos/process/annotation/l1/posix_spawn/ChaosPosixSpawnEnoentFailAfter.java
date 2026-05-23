/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawn;

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
 * After {@link #successesBeforeFailure} successful {@code posix_spawn} calls, injects
 * {@code ENOENT} on every subsequent call, causing the calling code to observe a no-such-file
 * failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code ENOENT},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code posix_spawn}
 *       call returns {@code ENOENT} directly (POSIX spawn returns the error code, not -1).</li>
 *   <li>The calling code receives: return value {@code ENOENT} (2); no child process is created;
 *       the pid output parameter is not set to a valid value.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code ENOENT}; assert that the application includes the binary path in the error
 *       diagnostic and does not treat ENOENT as a retriable error — binary absence is a deployment
 *       failure that requires operator action, not in-process retry.</li>
 *   <li>FAIL_AFTER models the binary-disappearance scenario: N successful spawns occur before a
 *       rolling deployment removes or replaces the binary; subsequent spawns fail with ENOENT —
 *       assert that the application detects this deployment-integrity failure at the threshold.</li>
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       post-threshold ENOENT — POSIX does not define the pid value when spawn fails.</li>
 * </ul>
 * Production failure mode: a tool runner uses {@code posix_spawn} with a hardcoded binary path;
 * a rolling deployment updates the container image; the binary is temporarily unavailable during
 * the update window; after N successful spawns all subsequent spawns return ENOENT; the runner
 * has no deployment-integrity alert and operators cannot determine which binary is missing.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER models the rolling-deployment binary-absence scenario where N spawns succeed
 * before the binary is removed. Real ENOENT from posix_spawn in this scenario is not probabilistic
 * — it occurs once and persists until the binary is restored. POSIX spawn returns the error code
 * directly — checking {@code if (ret < 0)} misses ENOENT (2). The counter does not reset at class
 * scope, enabling sequential testing of the success phase and the ENOENT-alert phase without
 * redeploying the container.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEnoentFailAfter(successesBeforeFailure = 20)
 * class PosixSpawnBinaryDisappearanceTest {
 *   @Test
 *   void runnerReportsPathOnEnoentAfterThresholdAndDoesNotRetry(ConnectionInfo info) {
 *     // first 20 spawns succeed; subsequent spawns return ENOENT;
 *     // verify binary path in diagnostic; deployment alert raised; no retry; no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of successful spawns before the deployment window opens; values 5–100 cover most scenarios;
 * 0 means the binary is missing from the first spawn (cold deployment error).
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnEnoentFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.ENOENT)
public @interface ChaosPosixSpawnEnoentFailAfter {

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
   * @ChaosPosixSpawnEnoentFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnEnoentFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnEnoentFailAfter[] value();
  }
}
