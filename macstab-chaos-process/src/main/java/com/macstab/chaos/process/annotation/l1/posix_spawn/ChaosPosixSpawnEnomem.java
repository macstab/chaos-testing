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
 * Injects {@code ENOMEM} into {@code posix_spawn} calls intercepted by libchaos-process, causing
 * the calling code to observe an out-of-memory failure when attempting to spawn a new process.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code ENOMEM})
 * tuple. The {@code POSIX_SPAWN} selector intercepts {@code posix_spawn} calls only, leaving {@code
 * posix_spawnp}, {@code fork}, {@code execve}, and all other process syscalls unaffected.
 * Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.
 *   <li>On each {@code posix_spawn} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.
 *   <li>When the trial fires, the interposer returns {@code ENOMEM} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.
 *   <li>The calling code receives: return value {@code ENOMEM} (12), {@code strerror}: "Out of
 *       memory"; no child process is created; the calling process is in a clean state since no
 *       child resources were allocated before the interposer fired.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code posix_spawn} returns {@code ENOMEM}; no child process is created; assert that the
 *       application reports a memory-pressure diagnostic and applies backoff — ENOMEM from
 *       posix_spawn may persist for the duration of a node memory pressure event and requires
 *       longer backoff than the transient EAGAIN case.
 *   <li>Assert that the application does not call {@code waitpid} on the uninitialised pid output
 *       parameter after an ENOMEM failure — POSIX does not define the pid value when spawn fails.
 *   <li>Assert that the application distinguishes {@code posix_spawn}-ENOMEM (kernel memory
 *       structures unavailable, requires node-level memory metric check) from EAGAIN (process table
 *       or uid quota exhausted, self-healing when children exit) — the two require different retry
 *       strategies and different operator actions.
 * </ul>
 *
 * Production failure mode: a job runner uses {@code posix_spawn} to launch worker processes; the
 * node is under memory pressure from OOM-protected workloads; spawn returns ENOMEM; the runner's
 * error handler treats ENOMEM identically to EAGAIN and applies a short retry with exponential
 * backoff, but the memory condition persists longer than the backoff budget, causing the runner to
 * emit a "spawn failed permanently" alert that triggers an unnecessary node replacement.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ENOMEM} from {@code posix_spawn} occurs when the kernel cannot allocate the data
 * structures required for the child process (task_struct, kernel stack, mm_struct) or when the
 * glibc spawn helper cannot allocate its internal communication pipe or argument-copy buffer. The
 * glibc implementation of {@code posix_spawn} on Linux uses a helper thread or helper process to
 * perform the file-actions and exec; if kernel memory is insufficient to create this helper, ENOMEM
 * is returned before any fork occurs.
 *
 * <p>The POSIX return-value convention is important: {@code posix_spawn} returns the error code
 * directly, not -1. Code that checks {@code if (ret == -1)} or {@code if (errno == ENOMEM)} after
 * {@code posix_spawn} without first checking {@code ret} is incorrect — the interposer returns
 * ENOMEM as the return value, consistent with the POSIX specification. Applications that mix {@code
 * fork}/{@code exec} error-handling patterns with spawn error-handling will fail silently when
 * ENOMEM is returned.
 *
 * <p>Unlike ENOMEM from {@code execve} or {@code fork}, ENOMEM from {@code posix_spawn} leaves the
 * calling process in a completely clean state: no child was created, no fds were allocated for the
 * spawn communication, and no argument structures were partially initialised in the child. The only
 * cleanup required is to release any application-level resources allocated in preparation for the
 * spawn (argument arrays, attribute structures), which should be destroyed with {@code
 * posix_spawnattr_destroy} and {@code posix_spawn_file_actions_destroy} regardless of whether the
 * spawn succeeded or failed.
 *
 * <p>Retry strategy for ENOMEM differs from EAGAIN: EAGAIN from spawn indicates process table or
 * uid quota exhaustion, which is self-healing once children exit; ENOMEM indicates kernel memory
 * exhaustion, which may persist for minutes if the node is in an OOM-reclaim loop. Applications
 * should implement a longer initial backoff for ENOMEM (e.g. 5–30 seconds) with a cap on the number
 * of retries, rather than treating it as equivalent to EAGAIN.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEnomem(probability = 0.001)
 * class PosixSpawnMemoryPressureTest {
 *   @Test
 *   void runnerAppliesLongerBackoffOnEnomemThanOnEagainAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // verify ENOMEM backoff longer than EAGAIN; no waitpid on uninit pid; memory alert raised
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; ENOMEM from posix_spawn is rare in
 * well-provisioned environments but the silent-failure risk makes coverage valuable.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.ENOMEM)
public @interface ChaosPosixSpawnEnomem {

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
   * @ChaosPosixSpawnEnomem(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnEnomem(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnEnomem[] value();
  }
}
