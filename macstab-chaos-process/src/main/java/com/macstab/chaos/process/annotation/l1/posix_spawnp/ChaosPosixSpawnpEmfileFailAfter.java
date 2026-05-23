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
 * After {@link #successesBeforeFailure} successful {@code posix_spawnp} calls, injects
 * {@code EMFILE} on every subsequent call, causing the calling code to observe a persistent
 * per-process fd-table exhaustion failure that models a fd leak accumulation threshold.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code EMFILE},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawnp} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code posix_spawnp}
 *       call returns {@code EMFILE} directly (POSIX spawn returns the error code, not -1).</li>
 *   <li>The calling code receives: return value {@code EMFILE} (24); no child process is created;
 *       the process's fd table has reached {@code RLIMIT_NOFILE}.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code EMFILE}; assert that the application includes the current fd count in the
 *       error diagnostic and does not call {@code waitpid} on an uninitialised pid.</li>
 *   <li>FAIL_AFTER models the fd-leak accumulation threshold: each successful spawnp opens and
 *       should close pipe fds for subprocess communication; leaked pipe read-ends accumulate across
 *       spawns; after N spawns the fd table is full; assert that the application's diagnostic
 *       reports the open fd count and identifies the source of the leak.</li>
 *   <li>Assert that the application distinguishes post-threshold EMFILE (24, per-process fd table
 *       full, fixable by closing leaked fds) from ENFILE (23, system-wide, requires platform team
 *       escalation) — in-process fd cleanup resolves EMFILE but not ENFILE.</li>
 * </ul>
 * Production failure mode: a shell command executor uses {@code posix_spawnp} to run utilities;
 * each command capture leaks a pipe read-end fd; after N spawns the fd table is full; spawnp
 * returns EMFILE; the executor does not report the fd count, leaving operators unable to determine
 * whether the failure is per-process (EMFILE, fixable in-process) or system-wide (ENFILE,
 * requiring platform escalation) without inspecting {@code /proc/pid/fd}.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER models the fd-leak accumulation threshold: glibc's posix_spawnp uses an internal
 * parent-child error-reporting pipe, which requires two fd slots from the parent's fd table. Each
 * successful spawn that captures output also allocates a capture pipe. If pipe read-ends are not
 * closed after each child exits, the fd count rises by approximately two per spawn cycle. After N
 * cycles the fd table (capped at RLIMIT_NOFILE) is full and the next spawn's internal pipe
 * allocation fails with EMFILE. POSIX spawn returns the error code directly — checking
 * {@code if (ret < 0)} silently misses EMFILE (24).
 *
 * <p>The {@code $PATH} search in posix_spawnp adds additional fd pressure: each PATH directory is
 * opened with {@code opendir} during the search. Under a nearly-full fd table, the PATH search
 * itself may consume the last remaining slots and cause EMFILE before the spawn's pipe allocation.
 * This means the effective EMFILE threshold for posix_spawnp may be slightly lower than for
 * posix_spawn under identical fd counts — an application that calibrates its fd headroom based on
 * posix_spawn behaviour may hit EMFILE sooner with posix_spawnp.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises the success path (calls 1 through N,
 * simulating the leak accumulation phase); subsequent test methods exercise the EMFILE path
 * automatically. Set {@link #successesBeforeFailure} based on the estimated RLIMIT_NOFILE divided
 * by the per-spawn fd leak rate to model the exact threshold.
 *
 * <p>Post-threshold recovery: once EMFILE is hit, the application must close leaked fds before
 * retrying spawns. In-process fd auditing (enumerate {@code /proc/self/fd}) can identify leaked
 * pipe ends; the application should close fds that are no longer associated with live children and
 * retry. Unlike ENFILE (system-wide) or EAGAIN (self-heals), EMFILE requires explicit in-process
 * cleanup before spawns can succeed again.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEmfileFailAfter(successesBeforeFailure = 40)
 * class PosixSpawnpFdLeakThresholdTest {
 *   @Test
 *   void executorReportsFdCountOnEmfileAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // first 40 spawns succeed (leak accumulation phase);
 *     // subsequent spawns return EMFILE; verify fd count in diagnostic; EMFILE vs ENFILE
 *     // distinguished; in-process fd audit triggered; no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to
 * {@code RLIMIT_NOFILE / leakRatePerSpawn}; values 20–100 cover typical fd-leak scenarios;
 * 0 means every spawn fails immediately (fd table pre-exhausted).
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnpEmfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.EMFILE)
public @interface ChaosPosixSpawnpEmfileFailAfter {

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
   * @ChaosPosixSpawnpEmfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnpEmfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnpEmfileFailAfter[] value();
  }
}
