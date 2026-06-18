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
 * Injects {@code ENOMEM} into {@code posix_spawnp} calls intercepted by libchaos-process, causing
 * the calling code to observe a kernel out-of-memory failure when attempting to spawn a new process
 * via {@code $PATH} lookup.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code ENOMEM})
 * tuple. The {@code POSIX_SPAWNP} selector intercepts {@code posix_spawnp} calls only, leaving
 * {@code posix_spawn}, {@code fork}, and all other process syscalls unaffected. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawnp} wrapper at the dynamic-linker level.
 *   <li>On each {@code posix_spawnp} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.
 *   <li>When the trial fires, the interposer returns {@code ENOMEM} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.
 *   <li>The calling code receives: return value {@code ENOMEM} (12), {@code strerror}: "Cannot
 *       allocate memory"; glibc's internal spawn helper or the kernel could not allocate the
 *       structures required to create a new process; no child is created and the calling process is
 *       in a clean state.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code posix_spawnp} returns {@code ENOMEM}; no child process is created; the calling
 *       process is in a clean state (no partial child resources allocated); assert that the
 *       application checks the return value directly (POSIX spawn returns the error code, not -1)
 *       and applies a longer backoff than for EAGAIN — ENOMEM may persist for the duration of a
 *       node memory pressure event.
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       ENOMEM — POSIX does not define the pid output parameter value when spawn fails; calling
 *       waitpid on an uninitialised pid may block indefinitely or wait on an unrelated process.
 *   <li>Assert that the application distinguishes {@code posix_spawnp}-ENOMEM (kernel OOM, requires
 *       node-level intervention or GC pressure relief) from EAGAIN (uid process count exhausted,
 *       self-heals when children exit) — the retry interval and escalation path differ: EAGAIN
 *       warrants short exponential backoff; ENOMEM warrants longer intervals with memory alert.
 * </ul>
 *
 * Production failure mode: a service uses {@code posix_spawnp} to invoke utilities by name during
 * batch processing; the Kubernetes node is under memory pressure from OOM-protected workloads;
 * glibc's spawn helper cannot allocate the internal communication pipe; posix_spawnp returns
 * ENOMEM; the service applies the same short retry as for EAGAIN, issuing repeated failed spawn
 * attempts that consume memory in their own right and worsen the node's memory pressure.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ENOMEM} from {@code posix_spawnp} originates from the same glibc internal structure as
 * {@code posix_spawn}: glibc's helper allocates an internal parent-child error-reporting pipe and
 * various working buffers before the fork; when the slab allocator is exhausted, these allocations
 * fail with ENOMEM and the spawn returns the error code directly. The {@code $PATH} search that
 * distinguishes spawnp from spawn occurs before these allocations — if the PATH search exhausts
 * memory via its directory traversal, the error may also appear as ENOMEM, though the PATH search
 * is typically lightweight.
 *
 * <p>POSIX spawn returns the error code directly — checking {@code if (ret < 0)} or {@code if (ret
 * == -1)} silently misses ENOMEM (12). Code that tests {@code if (ret != 0)} is correct. ENOMEM
 * (12) and EAGAIN (11) are adjacent integers; off-by-one errors in switch statements can cause
 * misclassification between these two errnos.
 *
 * <p>Unlike EAGAIN (which self-heals when children exit), ENOMEM persists until the kernel's memory
 * reclaim makes free memory available. Retry intervals should be substantially longer than for
 * EAGAIN — a minimum of several seconds before the first retry, with exponential backoff capped at
 * a value that does not worsen node memory pressure. Under real node OOM conditions, the kernel OOM
 * killer may terminate processes concurrently with the application's retries; the application must
 * handle SIGKILL of child processes during this window.
 *
 * <p>The calling process is always in a clean state after posix_spawnp ENOMEM — no child process
 * was created, no child resources were allocated. This is in contrast to fork+exec patterns where
 * ENOMEM from fork may leave a partially-duplicated address space in an error path. Applications
 * using posix_spawnp do not need fork-error cleanup; they only need to avoid calling waitpid on the
 * uninitialised pid parameter.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEnomem(probability = 0.001)
 * class PosixSpawnpMemoryPressureTest {
 *   @Test
 *   void executorAppliesLongerBackoffOnEnomemThanEagainAndDoesNotWaitOnUninitPid(ConnectionInfo info) {
 *     // verify ENOMEM distinguished from EAGAIN; longer backoff applied; memory alert raised;
 *     // no waitpid on uninit pid; return value checked (not errno)
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; ENOMEM represents serious node memory
 * pressure; low probability exercises the memory-alert escalation path without preventing the
 * application from functioning.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnpEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.ENOMEM)
public @interface ChaosPosixSpawnpEnomem {

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
   * @ChaosPosixSpawnpEnomem(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnpEnomem(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnpEnomem[] value();
  }
}
