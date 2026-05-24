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
 * ENOENT} on every subsequent call, causing the calling code to observe a persistent
 * binary-not-found failure that models a binary disappearing from {@code $PATH} during a rolling
 * deployment.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code ENOENT},
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
 *       call returns {@code ENOENT} directly (POSIX spawn returns the error code, not -1).
 *   <li>The calling code receives: return value {@code ENOENT} (2); no child process is created;
 *       the binary name was not found in any directory listed in {@code $PATH}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code ENOENT}; assert that the application includes the binary name and the {@code
 *       $PATH} value in the error diagnostic and raises a deployment-integrity alert — binary
 *       disappearance is a deployment error requiring operator action, not in-process retry.
 *   <li>FAIL_AFTER models the binary-disappearance scenario: N successful spawns occur while the
 *       binary is present in a {@code $PATH} directory; a rolling deployment removes or replaces
 *       the binary in that directory; subsequent spawns fail with ENOENT — assert that the
 *       application detects this deployment-integrity failure at the threshold and reports which
 *       binary name is missing.
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       post-threshold ENOENT, and does not retry — ENOENT from posix_spawnp is not transient;
 *       assert that the application distinguishes posix_spawnp-ENOENT (binary not in any PATH
 *       directory) from posix_spawn-ENOENT (specific explicit path missing) in error messages.
 * </ul>
 *
 * Production failure mode: a service uses {@code posix_spawnp} to invoke a helper utility by name;
 * a rolling deployment updates the container image; during the update window the binary is
 * temporarily absent from its {@code $PATH} directory; after N successful spawns all subsequent
 * spawns return ENOENT; the service has no deployment-integrity alert and operators cannot
 * determine which binary name is missing or which PATH directories were searched.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the rolling-deployment binary-absence scenario: N spawns succeed while the
 * binary is present; at the deployment boundary the binary is removed from the PATH directory;
 * subsequent spawns return ENOENT after exhausting the PATH search. Real ENOENT from this scenario
 * is not probabilistic — it fires deterministically once the binary is removed and persists until
 * the binary is restored. POSIX spawn returns the error code directly — checking {@code if (ret <
 * 0)} silently misses ENOENT (2).
 *
 * <p>The posix_spawnp ENOENT scenario differs from posix_spawn ENOENT in the diagnostic information
 * required: spawnp ENOENT means the binary name (the {@code file} argument) was not found in any
 * directory listed in the {@code $PATH} environment variable; the error message should include both
 * the binary name and the current PATH value so that operators can determine which directories were
 * searched. posix_spawn ENOENT means a specific explicit path was invalid, and the error message
 * should include the explicit path.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises the success path (calls 1 through N,
 * simulating normal operation before the deployment window); subsequent test methods exercise the
 * ENOENT path automatically without redeploying the container. Set {@link #successesBeforeFailure}
 * to the expected number of spawns before the deployment window opens.
 *
 * <p>Unlike EAGAIN (which warrants retry) or EMFILE (which warrants fd cleanup before retry),
 * ENOENT from posix_spawnp warrants neither retry nor in-process recovery. The application should
 * open a circuit-breaker immediately and alert the deployment pipeline or on-call team with the
 * missing binary name and PATH. Retry loops on ENOENT are not only useless but dangerous — they
 * produce log storms that can obscure other concurrent alerts during a deployment incident.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEnoentFailAfter(successesBeforeFailure = 25)
 * class PosixSpawnpBinaryDisappearanceTest {
 *   @Test
 *   void executorReportsBinaryNameAndPathOnEnoentAfterThresholdAndAlertsDeploymentTeam(ConnectionInfo info) {
 *     // first 25 spawns succeed; subsequent spawns return ENOENT;
 *     // verify binary name and $PATH in diagnostic; deployment alert raised; no retry; no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of successful spawns before the deployment window opens; values 5–100 cover most
 * deployment scenarios; 0 means the binary is missing from the first spawn (cold deployment error).
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnpEnoentFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.ENOENT)
public @interface ChaosPosixSpawnpEnoentFailAfter {

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
   * @ChaosPosixSpawnpEnoentFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnpEnoentFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnpEnoentFailAfter[] value();
  }
}
