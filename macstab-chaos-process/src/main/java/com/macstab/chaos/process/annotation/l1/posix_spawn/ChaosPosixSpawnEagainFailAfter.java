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
 * After {@link #successesBeforeFailure} successful {@code posix_spawn} calls, injects {@code
 * EAGAIN} on every subsequent call, causing the calling code to observe a
 * resource-temporarily-unavailable failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code EAGAIN},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code posix_spawn}
 *       call returns {@code EAGAIN} directly (POSIX spawn returns the error code, not -1).
 *   <li>The calling code receives: return value {@code EAGAIN} (11); no child process is created;
 *       the pid output parameter is not set to a valid value.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code EAGAIN}; assert that the application checks the return value (not errno) and
 *       applies backoff rather than treating post-threshold EAGAIN as a permanent failure.
 *   <li>FAIL_AFTER models the {@code RLIMIT_NPROC} exhaustion threshold exactly: the uid's process
 *       count rises with each spawn that has not yet been waited on; after N spawns the limit is
 *       hit; assert that the application implements load-shedding or backpressure at the threshold.
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       post-threshold EAGAIN — POSIX does not define the pid value when spawn fails.
 * </ul>
 *
 * Production failure mode: a job runner uses {@code posix_spawn} to launch workers; the uid's
 * process count approaches {@code RLIMIT_NPROC} as jobs accumulate; after N successful spawns all
 * subsequent spawns return EAGAIN; the runner has no upper bound on concurrent spawns and no
 * backpressure logic, so all jobs during the saturation window fail.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the {@code RLIMIT_NPROC} exhaustion curve more accurately than probabilistic
 * ERRNO for posix_spawn: the first N spawns succeed as the uid's process count rises; the N+1th
 * spawn hits the limit deterministically. The POSIX return-value convention is critical — {@code
 * posix_spawn} returns the error code directly, so checking {@code if (ret < 0)} misses EAGAIN
 * (11). The counter does not reset between test methods at class scope, enabling a test class to
 * verify the success phase and the failure-with-backpressure phase sequentially.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEagainFailAfter(successesBeforeFailure = 64)
 * class PosixSpawnProcessLimitExhaustionTest {
 *   @Test
 *   void runnerAppliesBackpressureAfterEagainThreshold(ConnectionInfo info) {
 *     // first 64 spawn calls succeed; subsequent calls return EAGAIN;
 *     // verify return value checked; backpressure applied; no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * concurrent spawn count before the uid's {@code RLIMIT_NPROC} is reached; values 20–200 cover most
 * job-runner scenarios; 0 means the limit is hit from the first spawn.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnEagainFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.EAGAIN)
public @interface ChaosPosixSpawnEagainFailAfter {

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
   * @ChaosPosixSpawnEagainFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnEagainFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnEagainFailAfter[] value();
  }
}
