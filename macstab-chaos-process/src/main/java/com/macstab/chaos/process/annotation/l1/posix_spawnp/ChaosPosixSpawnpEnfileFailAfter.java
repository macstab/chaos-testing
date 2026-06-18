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
 * ENFILE} on every subsequent call, causing the calling code to observe a persistent system-wide
 * file-table exhaustion failure that models node-level fd saturation.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code ENFILE},
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
 *       call returns {@code ENFILE} directly (POSIX spawn returns the error code, not -1).
 *   <li>The calling code receives: return value {@code ENFILE} (23); no child process is created;
 *       the kernel's global open-file table ({@code fs.file-max}) has been exhausted system-wide.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code ENFILE}; assert that the application escalates to the platform team rather
 *       than attempting in-process recovery — ENFILE cannot be resolved by closing
 *       application-owned fds alone; assert that {@code /proc/sys/fs/file-nr} metrics are included
 *       in the alert.
 *   <li>FAIL_AFTER models the node saturation curve: N spawns contribute to the system-wide file
 *       count via their internal pipes; after N spawns the kernel's global table reaches {@code
 *       fs.file-max}; subsequent spawns from any process on the node fail with ENFILE — assert that
 *       the application raises a circuit-breaker and stops spawning rather than retrying
 *       indefinitely into a saturated node.
 *   <li>Assert that the application distinguishes post-threshold ENFILE (23, system-wide, requires
 *       platform team — node intervention) from EMFILE (24, per-process fd table, fixable by
 *       in-process fd cleanup) — the operator runbook and escalation path differ; in-process fd
 *       cleanup resolves EMFILE but not ENFILE.
 * </ul>
 *
 * Production failure mode: a container runs a high-throughput command executor using {@code
 * posix_spawnp} to invoke utilities by name; aggregate pipe fd allocations across all containers on
 * the node fill the kernel's global file table; after N successful spawns all subsequent spawns
 * return ENFILE; the executor retries with the same interval as for EAGAIN, which is inappropriate
 * for ENFILE and worsens node saturation; the platform team is not alerted because the application
 * does not distinguish ENFILE from EMFILE.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER models the node saturation curve: each successful posix_spawnp allocates file-table
 * entries for the spawn's internal error-reporting pipe and for the process's own
 * subprocess-capture pipes. The kernel's global file table (bounded by {@code fs.file-max}) is
 * shared across all processes on the host. As the aggregate count of open file descriptions rises
 * across all containers, the global table fills; after N spawns from the container under test the
 * table is exhausted and ENFILE is returned from the next spawn. POSIX spawn returns the error code
 * directly — checking {@code if (ret < 0)} silently misses ENFILE (23).
 *
 * <p>The {@code $PATH} directory traversal in posix_spawnp adds additional file-table pressure
 * compared to posix_spawn: each PATH directory is opened via {@code opendir}, consuming a
 * file-table entry during the traversal. Under a nearly-saturated node, the PATH traversal itself
 * may push the global table over the limit, causing ENFILE before the fork phase. The interposer
 * fires at the API boundary and covers both cases.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises the success path (calls 1 through N,
 * simulating normal operation during node saturation buildup); subsequent test methods exercise the
 * ENFILE path automatically. Set {@link #successesBeforeFailure} based on the estimated {@code
 * fs.file-max} minus the current node file count divided by the per-spawn file-table entry
 * contribution.
 *
 * <p>Recovery from ENFILE requires node-level intervention: increasing {@code fs.file-max} via
 * sysctl, or reducing the number of open file descriptions across containers. Applications cannot
 * resolve ENFILE by closing their own fds in isolation — the global table may still be full from
 * other processes. The circuit-breaker should open on sustained ENFILE and alert the platform team
 * with the {@code /proc/sys/fs/file-nr} reading at the time of the first failure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEnfileFailAfter(successesBeforeFailure = 75)
 * class PosixSpawnpNodeSaturationThresholdTest {
 *   @Test
 *   void executorAlertsWithFileNrOnEnfileAndOpensPlatformCircuitBreaker(ConnectionInfo info) {
 *     // first 75 spawns succeed; subsequent spawns return ENFILE;
 *     // verify ENFILE distinguished from EMFILE; file-nr metric in alert; circuit-breaker opens;
 *     // no retry into saturated node; no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the estimated
 * number of spawns before the node's global file table fills; values 20–200 cover realistic
 * saturation curves; 0 means the node is saturated before the first spawn (pre-existing
 * saturation).
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnpEnfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.ENFILE)
public @interface ChaosPosixSpawnpEnfileFailAfter {

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
   * @ChaosPosixSpawnpEnfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnpEnfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnpEnfileFailAfter[] value();
  }
}
