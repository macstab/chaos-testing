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
 * Injects {@code EAGAIN} into {@code posix_spawnp} calls intercepted by libchaos-process, causing
 * the calling code to observe a resource-temporarily-unavailable failure when attempting to spawn
 * a new process via {@code $PATH} lookup.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code EAGAIN})
 * tuple. The {@code POSIX_SPAWNP} selector intercepts {@code posix_spawnp} calls only, leaving
 * {@code posix_spawn}, {@code fork}, {@code execve}, and all other process syscalls unaffected.
 * Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawnp} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code posix_spawnp} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer returns {@code EAGAIN} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.</li>
 *   <li>The calling code receives: return value {@code EAGAIN} (11),
 *       {@code strerror}: "Resource temporarily unavailable"; no child process is created and the
 *       pid output parameter is not set to a valid value.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code posix_spawnp} returns {@code EAGAIN}; no child process is created; assert that
 *       the application checks the return value (not errno — POSIX spawn returns the error code
 *       directly) and retries with backoff rather than treating EAGAIN as a permanent failure.</li>
 *   <li>Applications using {@code posix_spawnp} for tool invocation (shell commands, helper
 *       utilities looked up via {@code $PATH}) must handle EAGAIN without treating the pid output
 *       parameter as valid — assert that the application does not call {@code waitpid} on an
 *       uninitialised pid after a spawn failure.</li>
 *   <li>Assert that the application distinguishes {@code posix_spawnp}-EAGAIN (process table or
 *       uid quota exhausted, transient) from ENOENT (binary not found in any {@code $PATH}
 *       directory, deployment error) — retry is appropriate for EAGAIN but not for ENOENT.</li>
 * </ul>
 * Production failure mode: a pipeline tool uses {@code posix_spawnp} to invoke helper utilities
 * by name; a burst of parallel pipeline runs exhausts the uid's {@code RLIMIT_NPROC}; spawnp
 * returns EAGAIN; the tool does not check the return value and calls {@code waitpid} on the
 * uninitialised pid, which may block indefinitely or wait on an unrelated process.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code posix_spawnp} differs from {@code posix_spawn} only in the executable lookup: spawnp
 * searches each directory in {@code $PATH} for the filename, while spawn takes an explicit path.
 * For EAGAIN, the distinction is irrelevant at the kernel level — both calls use the same fork/exec
 * sequence, and EAGAIN from process-table or uid-quota exhaustion occurs regardless of how the
 * binary path was resolved. The POSIX return-value convention applies to both: return the error
 * code directly, not -1. Code that checks {@code if (ret == -1)} or {@code if (errno == EAGAIN)}
 * without first checking the return value silently misses the EAGAIN return.
 *
 * <p>The {@code $PATH} environment variable search in {@code posix_spawnp} adds a subtle timing
 * window not present in {@code posix_spawn}: the PATH search occurs in the parent before the
 * fork, which means multiple fds may be opened for directory traversal during the search. If the
 * fd table is nearly full, the PATH search itself may fail with EMFILE before the spawn's
 * EAGAIN would occur. The chaos annotation fires at the spawn API boundary, covering the case
 * where resources are exhausted after the PATH search succeeds but before the fork completes.
 *
 * <p>The retry strategy for EAGAIN from spawnp is identical to that for spawn: EAGAIN indicates
 * process table or uid quota exhaustion, which self-heals when children exit. Applications should
 * implement a bounded retry with exponential backoff and apply load-shedding if the EAGAIN
 * persists beyond the retry budget.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEagain(probability = 0.01)
 * class PosixSpawnpProcessTablePressureTest {
 *   @Test
 *   void toolRunnerRetriesOnEagainAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // verify return value checked; no waitpid on uninit pid; backoff retry applied
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EAGAIN from posix_spawnp is transient
 * and the application should retry; any non-zero probability exercises the retry path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnpEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.EAGAIN)
public @interface ChaosPosixSpawnpEagain {

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
   * @ChaosPosixSpawnpEagain(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnpEagain(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnpEagain[] value();
  }
}
