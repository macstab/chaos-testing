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
 * EMFILE} on every subsequent call, causing the calling code to observe a per-process fd-table
 * exhaustion failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code EMFILE},
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
 *       call returns {@code EMFILE} directly (POSIX spawn returns the error code, not -1).
 *   <li>The calling code receives: return value {@code EMFILE} (24); the process's fd table has
 *       reached {@code RLIMIT_NOFILE}; no child process is created.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code EMFILE}; assert that the application reports the current fd count in the
 *       diagnostic and does not call {@code waitpid} on an uninitialised pid after the failure.
 *   <li>FAIL_AFTER models the progressive fd-leak accumulation pattern: leaked pipe fds from
 *       previous spawns accumulate until the table is full; after N successful spawns all
 *       subsequent spawns return EMFILE — assert that the application implements pipe fd cleanup
 *       and reports the fd count at the failure point.
 *   <li>Assert that the application distinguishes {@code posix_spawn}-EMFILE (24, per-process) from
 *       ENFILE (23, system-wide) — EMFILE may be fixable in-process by closing leaked pipes; ENFILE
 *       requires platform escalation.
 * </ul>
 *
 * Production failure mode: a subprocess launcher uses {@code posix_spawn} with pipe file-actions
 * for stdout/stderr capture; leaked pipe read-ends accumulate with each spawn invocation; after N
 * spawns the fd table reaches {@code RLIMIT_NOFILE}; the launcher returns EMFILE without reporting
 * the fd count, making fd-leak root cause analysis impossible without external tools.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the progressive pipe-fd leak accumulation curve: each spawn consumes two
 * pipe fds (parent's read-end) for stdout/stderr capture; if these are not closed after the child
 * exits, they accumulate until EMFILE. Setting {@link #successesBeforeFailure} to the observed
 * spawn count before the table fills reproduces this threshold. POSIX spawn returns the error code
 * directly — checking {@code if (ret < 0)} misses EMFILE (24). The counter does not reset at class
 * scope, enabling success-phase and failure-phase testing in sequential test methods.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEmfileFailAfter(successesBeforeFailure = 100)
 * class PosixSpawnFdLeakAccumulationTest {
 *   @Test
 *   void launcherReportsFdCountOnEmfileAfterThreshold(ConnectionInfo info) {
 *     // first 100 spawns succeed; subsequent spawns return EMFILE;
 *     // verify fd count reported; no waitpid on uninit pid; EMFILE vs ENFILE distinct
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> derive from observed pipe fd leak rate and {@code
 * RLIMIT_NOFILE}; values 10–200 exercise the failure mode efficiently; 0 tests cold-start fd
 * exhaustion.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnEmfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.EMFILE)
public @interface ChaosPosixSpawnEmfileFailAfter {

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
   * @ChaosPosixSpawnEmfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnEmfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnEmfileFailAfter[] value();
  }
}
