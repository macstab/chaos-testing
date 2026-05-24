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
 * EAGAIN} on every subsequent call, causing the calling code to observe a
 * resource-temporarily-unavailable failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code EAGAIN},
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
 *       call returns {@code EAGAIN} directly (POSIX spawn returns the error code, not -1).
 *   <li>The calling code receives: return value {@code EAGAIN} (11); no child process is created;
 *       the pid output parameter is not set to a valid value.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code EAGAIN}; assert that the application checks the return value (POSIX spawn
 *       returns the error code directly, not -1) and retries with exponential backoff rather than
 *       treating EAGAIN as permanent — EAGAIN self-heals when children exit.
 *   <li>FAIL_AFTER models the uid process-count ceiling ({@code RLIMIT_NPROC}): the first N spawns
 *       succeed while the uid's process count is below the limit; after N spawns the limit is
 *       reached and all subsequent spawns return EAGAIN — assert that the application detects this
 *       threshold and applies load-shedding or child-reaping before retrying further spawns.
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       post-threshold EAGAIN — POSIX does not define the pid value when spawn fails.
 * </ul>
 *
 * Production failure mode: a pipeline tool uses {@code posix_spawnp} to run helper utilities by
 * name; a burst of parallel pipeline runs pushes the uid's process count to RLIMIT_NPROC; after N
 * successful spawns all subsequent spawns return EAGAIN; the tool does not check the return value
 * and calls {@code waitpid} on the uninitialised pid, blocking indefinitely or waiting on an
 * unrelated process.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the uid process-count ceiling: as the process count rises to RLIMIT_NPROC,
 * spawns succeed until the ceiling is reached; subsequent spawns fail with EAGAIN. Real EAGAIN from
 * this source is not probabilistic — it fires deterministically once the ceiling is hit and
 * self-heals when children exit and their process table entries are reaped. POSIX spawn returns the
 * error code directly — checking {@code if (ret < 0)} silently misses EAGAIN (11).
 *
 * <p>The distinction between EAGAIN and ENOMEM at the FAIL_AFTER threshold is operationally
 * important: EAGAIN means the uid process count is at its ceiling (add child-reaping or reduce
 * spawn rate); ENOMEM means the kernel's memory allocator is exhausted (reduce memory pressure,
 * escalate to platform team). Both return as non-negative return values from posix_spawnp — code
 * that does not check the return value silently misses both.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises the success path (calls 1 through N);
 * subsequent test methods automatically observe the EAGAIN path without reconfiguring the rule. Set
 * {@link #successesBeforeFailure} to the uid's effective RLIMIT_NPROC minus the process count at
 * test start to model the exact moment the ceiling is reached.
 *
 * <p>The {@code $PATH} search adds a subtle detail: if the uid process count reaches the ceiling
 * during the PATH traversal phase (which opens directory fds but does not fork), the EAGAIN is
 * returned from the fork step that follows — the PATH traversal succeeds and the binary is found,
 * but the fork fails. Applications should not assume that EAGAIN from posix_spawnp means the binary
 * was not found.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEagainFailAfter(successesBeforeFailure = 50)
 * class PosixSpawnpProcessCeilingTest {
 *   @Test
 *   void toolRunnerShedsLoadOnEagainAfterCeilingAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // first 50 spawns succeed; subsequent spawns return EAGAIN;
 *     // verify return value checked; backoff retry applied; child reaping triggered; no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of spawns before the uid process count reaches RLIMIT_NPROC; values 10–200 cover most
 * scenarios; 0 means the very first spawn fails (uid already at the ceiling).
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnpEagainFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.EAGAIN)
public @interface ChaosPosixSpawnpEagainFailAfter {

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
   * @ChaosPosixSpawnpEagainFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnpEagainFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnpEagainFailAfter[] value();
  }
}
