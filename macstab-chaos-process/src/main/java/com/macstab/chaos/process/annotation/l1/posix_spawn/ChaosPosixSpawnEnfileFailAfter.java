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
 * ENFILE} on every subsequent call, causing the calling code to observe a system-wide file-table
 * exhaustion failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code ENFILE},
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
 *       call returns {@code ENFILE} directly (POSIX spawn returns the error code, not -1).
 *   <li>The calling code receives: return value {@code ENFILE} (23); the kernel's global file table
 *       ({@code fs.file-max}) is exhausted; no child process is created; closing this process's fds
 *       will not resolve the condition.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code ENFILE}; assert that the application surfaces a platform-capacity alert —
 *       closing this process's fds will not help when the system-wide kernel file table is
 *       exhausted by other processes on the node.
 *   <li>FAIL_AFTER models the aggregate fd accumulation pattern across all processes on the node:
 *       after N spawns the cumulative fd usage crosses {@code fs.file-max}; assert that the
 *       application includes node-level fd metrics in its alert for operator diagnosis.
 *   <li>Assert that the application distinguishes ENFILE (23, system-wide, platform escalation)
 *       from EMFILE (24, per-process, closeable in-process) and does not call {@code waitpid} on
 *       the uninitialised pid output parameter after the failure.
 * </ul>
 *
 * Production failure mode: multiple spawn-heavy processes on a Kubernetes node contribute leaked
 * fds; after N spawns the node's aggregate fd count reaches {@code fs.file-max}; all subsequent
 * spawns return ENFILE; the applications that receive ENFILE instruct operators to close
 * per-process fds (EMFILE remediation) instead of escalating to the platform team (ENFILE
 * remediation).
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the node-level fd saturation curve: aggregate fd usage across all processes
 * rises with each spawn that leaks its internal communication pipe; after N spawns the total
 * crosses {@code fs.file-max} and all subsequent spawns return ENFILE. POSIX spawn returns the
 * error code directly — checking {@code if (ret < 0)} misses ENFILE (23). The EMFILE vs ENFILE
 * diagnostic distinction is critical: EMFILE is fixable per-process; ENFILE requires platform
 * intervention. The counter does not reset at class scope, enabling sequential verification of the
 * pre-saturation and post-saturation phases.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEnfileFailAfter(successesBeforeFailure = 50)
 * class PosixSpawnNodeFdSaturationTest {
 *   @Test
 *   void launcherEscalatesToPlatformAlertAfterEnfileThreshold(ConnectionInfo info) {
 *     // first 50 spawns succeed; subsequent spawns return ENFILE;
 *     // verify platform alert raised; ENFILE vs EMFILE distinct; no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the observed
 * number of spawns before node fd saturation; values 10–200 exercise the pattern efficiently; 0
 * tests cold-start node saturation.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnEnfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.ENFILE)
public @interface ChaosPosixSpawnEnfileFailAfter {

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
   * @ChaosPosixSpawnEnfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnEnfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnEnfileFailAfter[] value();
  }
}
