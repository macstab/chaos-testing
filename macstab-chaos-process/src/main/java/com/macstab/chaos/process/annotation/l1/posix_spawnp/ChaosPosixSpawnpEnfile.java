/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawnp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessErrnoBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Injects {@code ENFILE} into {@code posix_spawnp} calls intercepted by libchaos-process, causing
 * the calling code to observe a system-wide file-table exhaustion failure when attempting to spawn a
 * new process via {@code $PATH} lookup.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code ENFILE})
 * tuple. The {@code POSIX_SPAWNP} selector intercepts {@code posix_spawnp} calls only, leaving
 * {@code posix_spawn}, {@code fork}, and all other process syscalls unaffected. Compile-time safety:
 * invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawnp} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code posix_spawnp} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer returns {@code ENFILE} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.</li>
 *   <li>The calling code receives: return value {@code ENFILE} (23),
 *       {@code strerror}: "Too many open files in system"; the kernel's global file-table
 *       ({@code fs.file-max}) has been exhausted system-wide; no child process is created.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code posix_spawnp} returns {@code ENFILE}; no child process is created; assert that
 *       the application checks the return value directly (not {@code errno} — POSIX spawn returns
 *       the error code, not -1) and does not call {@code waitpid} on an uninitialised pid.</li>
 *   <li>ENFILE is system-wide — it cannot be resolved in-process by closing application-owned fds
 *       alone; assert that the application raises an escalation alert to the platform team rather
 *       than attempting in-process recovery; in-process fd cleanup reduces EMFILE but not ENFILE.</li>
 *   <li>Assert that the application distinguishes {@code posix_spawnp}-ENFILE (23, system-wide
 *       kernel table, requires platform team — check {@code /proc/sys/fs/file-nr}) from EMFILE (24,
 *       per-process fd table, fixable by closing leaked fds) — the operator runbook and escalation
 *       path differ fundamentally.</li>
 * </ul>
 * Production failure mode: a container runs a high-throughput command executor using
 * {@code posix_spawnp} to invoke utilities by name; the host node runs hundreds of containers
 * with subprocess-heavy workloads; the kernel's global file-table fills; spawnp returns ENFILE;
 * the executor retries on ENFILE with the same short backoff as for EAGAIN, which makes node
 * saturation worse; operators cannot distinguish the failure from EMFILE without checking
 * {@code /proc/sys/fs/file-nr}.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code ENFILE} from {@code posix_spawnp} has the same kernel origin as {@code ENFILE} from
 * {@code posix_spawn}: the kernel's global open-file table (controlled by {@code fs.file-max}) is
 * full at the instant glibc's internal helper tries to allocate a new file-table entry for the
 * spawn's internal error-reporting pipe. The {@code $PATH} directory traversal that distinguishes
 * spawnp from spawn also consumes file-table entries — during heavy node load the PATH traversal
 * itself may hit ENFILE before the fork phase. The interposer fires at the API boundary, covering
 * both cases.
 *
 * <p>POSIX spawn returns the error code directly — checking {@code if (ret < 0)} or
 * {@code if (ret == -1)} silently misses ENFILE (23). Checking {@code errno} after a non-negative
 * return is undefined behaviour. Code that tests {@code if (ret != 0)} is correct; code that tests
 * {@code if (ret == ENFILE)} after verifying {@code ret != 0} is correct. The distinction matters
 * when ENFILE (23) is one higher than EMFILE (24 — note: EMFILE=24, ENFILE=23 on Linux) — integer
 * comparison errors can cause misclassification.
 *
 * <p>The {@code /proc/sys/fs/file-nr} file reports three space-separated numbers: allocated
 * file handles, (unused — always 0 since kernel 2.6), and maximum file handles. Monitoring
 * {@code file-nr} during load tests reveals whether ENFILE from posix_spawnp is approaching a real
 * threshold. The {@code $PATH} traversal in posix_spawnp opens directory fds during the search,
 * which can push a near-full system table over the edge; this makes ENFILE slightly more likely
 * for posix_spawnp than for posix_spawn under the same node load.
 *
 * <p>Retry strategy for ENFILE must differ from EAGAIN: EAGAIN self-heals when children exit,
 * making short exponential backoff appropriate; ENFILE requires node-level intervention (increase
 * {@code fs.file-max} or reduce workload density) that cannot be performed from within the
 * container process. Applications should implement a circuit-breaker that opens on sustained ENFILE
 * and alerts the platform team, rather than a retry loop that drives more spawn attempts into an
 * already-saturated system.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEnfile(probability = 0.001)
 * class PosixSpawnpNodeSaturationTest {
 *   @Test
 *   void executorAlertsOnEnfileAndDoesNotRetryAsEagain(ConnectionInfo info) {
 *     // verify ENFILE distinguished from EMFILE; platform alert raised; circuit-breaker opens;
 *     // no waitpid on uninit pid; /proc/sys/fs/file-nr checked in diagnostic
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; ENFILE represents extreme node
 * saturation; low probability exercises the escalation path without continuously triggering it.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnpEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.ENFILE)
public @interface ChaosPosixSpawnpEnfile {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

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
   * @ChaosPosixSpawnpEnfile(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnpEnfile(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnpEnfile[] value();
  }
}
