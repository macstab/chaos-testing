/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawn;

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
 * Injects {@code ENFILE} into {@code posix_spawn} calls intercepted by libchaos-process, causing
 * the calling code to observe a system-wide file-table exhaustion failure when attempting to spawn
 * a new process.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code ENFILE})
 * tuple. The {@code POSIX_SPAWN} selector intercepts {@code posix_spawn} calls only, leaving
 * {@code posix_spawnp}, {@code fork}, {@code execve}, and all other process syscalls unaffected.
 * Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code posix_spawn} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer returns {@code ENFILE} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.</li>
 *   <li>The calling code receives: return value {@code ENFILE} (23),
 *       {@code strerror}: "Too many open files in system"; the kernel's global file table
 *       ({@code fs.file-max}) is exhausted; no child process is created; closing this process's
 *       fds will not resolve the condition.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code posix_spawn} returns {@code ENFILE}; no child process is created; assert that the
 *       application surfaces a platform-capacity alert rather than attempting in-process remediation
 *       — closing this process's fds will not restore capacity when the system-wide kernel file
 *       table is exhausted by other processes.</li>
 *   <li>Assert that the application distinguishes {@code posix_spawn}-ENFILE (23, system-wide
 *       kernel file table exhausted, requires platform team to raise {@code fs.file-max} or reduce
 *       aggregate fd usage) from EMFILE (24, per-process fd table full, fixable by closing leaked
 *       fds in this process) — the diagnostic must route to the correct operator runbook.</li>
 *   <li>Assert that the application does not call {@code waitpid} on the uninitialised pid output
 *       parameter after an ENFILE failure — POSIX does not define the pid value on spawn failure.</li>
 * </ul>
 * Production failure mode: a Kubernetes node runs multiple container runtimes that each use
 * {@code posix_spawn} to launch sidecar processes; accumulated fd leaks across all runtimes push
 * the node's aggregate fd usage towards {@code fs.file-max}; spawn intermittently returns ENFILE;
 * the runtimes do not distinguish ENFILE from EMFILE and instruct operators to check per-process
 * fd counts — operators close application fds but the system-wide count is unchanged.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code ENFILE} from {@code posix_spawn} occurs when the kernel's system-wide open-file
 * table ({@code fs.file-max}, visible in {@code /proc/sys/fs/file-max}) is full. Every open fd
 * in every process on the node counts against this limit. The glibc {@code posix_spawn}
 * implementation internally allocates fds for the parent-child communication pipe used to report
 * exec errors from the child back to the parent; if the system-wide table is full, this pipe
 * allocation fails with ENFILE before the fork even begins.
 *
 * <p>The return-value convention is important: {@code posix_spawn} returns the error code directly,
 * not -1. Code that checks {@code if (errno == ENFILE)} without checking the return value is
 * incorrect. The interposer returns ENFILE as the return value, matching the POSIX specification.
 *
 * <p>The operational distinction from EMFILE is critical for incident response: EMFILE (24) means
 * this process's fd table is full and can potentially be fixed by closing leaked fds within the
 * process; ENFILE (23) means the system-wide kernel file table is exhausted and requires either
 * a platform-level increase in {@code fs.file-max} or a coordinated reduction in aggregate fd
 * usage across all processes on the node. Applications that emit a single "too many files" error
 * for both cases will receive incorrect remediation instructions from operators.
 *
 * <p>The diagnostic for ENFILE from spawn should include the node-level metric
 * ({@code /proc/sys/fs/file-nr} shows used, free, and max) to help operators distinguish a
 * single-process fd leak (EMFILE pattern) from a multi-process cumulative leak (ENFILE pattern).
 * Including this metric in the spawn failure log entry enables operators to take the correct action
 * without requiring kernel tracing tools.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEnfile(probability = 0.001)
 * class PosixSpawnNodeFdSaturationTest {
 *   @Test
 *   void launcherEscalatesToPlatformAlertOnEnfileAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // verify ENFILE triggers platform alert; no waitpid on uninit pid; ENFILE vs EMFILE distinct
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; system-wide fd exhaustion is a
 * multi-tenant event; any non-zero probability exercises the platform-escalation path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.ENFILE)
public @interface ChaosPosixSpawnEnfile {

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
   * @ChaosPosixSpawnEnfile(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnEnfile(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnEnfile[] value();
  }
}
