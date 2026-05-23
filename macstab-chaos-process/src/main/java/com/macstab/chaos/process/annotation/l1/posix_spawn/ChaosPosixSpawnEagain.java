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
 * Injects {@code EAGAIN} into {@code posix_spawn} calls intercepted by libchaos-process, causing
 * the calling code to observe a resource-temporarily-unavailable failure when attempting to spawn
 * a new process.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code EAGAIN})
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
 *   <li>When the trial fires, the interposer sets {@code errno = EAGAIN} and returns the errno
 *       value directly (POSIX spawn returns the error code, not -1) without issuing the real
 *       kernel call.</li>
 *   <li>The calling code receives: return value {@code EAGAIN} (11),
 *       {@code strerror}: "Resource temporarily unavailable"; no child process is created and
 *       the {@code pid} output parameter is not set to a valid child pid.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code posix_spawn} returns {@code EAGAIN}; no child process is created; assert that the
 *       application checks the return value (not errno, since POSIX spawn returns the error code
 *       directly) and retries with backoff rather than treating EAGAIN as a permanent failure.</li>
 *   <li>Applications using {@code posix_spawn} for subprocess management (shell commands, tool
 *       invocations, sidecar launchers) must handle EAGAIN without treating the output {@code pid}
 *       parameter as valid — assert that the application does not call {@code waitpid} on an
 *       uninitialised pid after a spawn failure.</li>
 *   <li>Assert that the application distinguishes {@code posix_spawn}-EAGAIN (process table or
 *       uid quota exhausted, transient) from {@code posix_spawn}-ENOMEM (kernel memory structures
 *       unavailable, potentially persistent) — the two errors require different retry strategies.</li>
 * </ul>
 * Production failure mode: a job scheduling service uses {@code posix_spawn} to launch worker
 * processes for each job; a burst of jobs exhausts the uid's {@code RLIMIT_NPROC}; spawn returns
 * EAGAIN; the scheduler does not check the spawn return value and calls {@code waitpid} on the
 * uninitialised pid, which may block indefinitely or wait on an unrelated process.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code posix_spawn} has a different return-value convention from most POSIX functions:
 * it returns the error code directly rather than returning -1 and setting errno. This is a common
 * source of bugs: code that checks {@code if (ret == -1)} or {@code if (ret < 0)} after
 * {@code posix_spawn} will silently miss EAGAIN (11) because the check expects a negative value.
 * The interposer returns the errno value directly, matching the POSIX specification and ensuring
 * that the chaos fires in a way that is indistinguishable from a real kernel failure.
 *
 * <p>{@code posix_spawn} internally calls {@code fork} (or {@code clone}) and then {@code exec}
 * in the child. EAGAIN from the spawn API can originate from either the fork or exec phase:
 * from the fork phase if {@code RLIMIT_NPROC} is exhausted, or from the exec phase if the exec
 * itself would return EAGAIN (less common). The chaos annotation fires at the spawn API boundary,
 * so the application sees a unified EAGAIN regardless of which internal phase would have failed.
 *
 * <p>The spawn attribute structure ({@code posix_spawnattr_t}) and file actions
 * ({@code posix_spawn_file_actions_t}) are not allocated by the kernel — they are user-space
 * data structures. A spawn failure with EAGAIN does not require cleanup of the attribute or
 * file-actions structures; the application must call {@code posix_spawnattr_destroy} and
 * {@code posix_spawn_file_actions_destroy} only when it is done using those structures, not
 * as part of error handling for a specific spawn failure.
 *
 * <p>The contrast with {@code posix_spawnp} is important for path-resolution testing:
 * {@code posix_spawn} takes an absolute or relative path to the executable, while
 * {@code posix_spawnp} searches {@code $PATH}. EAGAIN from either API has the same semantics
 * (resource temporarily unavailable), but the failure mode for ENOENT differs — use
 * {@code ChaosPosixSpawnpEnoent} to test path-search failures.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEagain(probability = 0.01)
 * class PosixSpawnProcessTablePressureTest {
 *   @Test
 *   void schedulerRetriesSpawnOnEagainAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // verify spawn return value checked; no waitpid on uninit pid; backoff retry applied
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EAGAIN from posix_spawn is transient
 * and the application should retry; any non-zero probability exercises the retry path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.EAGAIN)
public @interface ChaosPosixSpawnEagain {

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
   * @ChaosPosixSpawnEagain(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnEagain(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnEagain[] value();
  }
}
