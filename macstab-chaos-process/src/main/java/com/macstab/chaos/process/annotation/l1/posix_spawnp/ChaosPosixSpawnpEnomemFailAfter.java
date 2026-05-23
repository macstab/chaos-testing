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
 * {@code ENOMEM} on every subsequent call, causing the calling code to observe a persistent
 * kernel out-of-memory failure that models progressive slab allocator exhaustion on a Kubernetes
 * node under memory pressure.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code ENOMEM},
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
 *       call returns {@code ENOMEM} directly (POSIX spawn returns the error code, not -1).</li>
 *   <li>The calling code receives: return value {@code ENOMEM} (12); no child process is created;
 *       the calling process is in a clean state since no child resources were allocated.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code ENOMEM}; assert that the application applies backoff with a longer interval
 *       than for EAGAIN — ENOMEM may persist for the duration of a node memory pressure event,
 *       whereas EAGAIN self-heals when children exit.</li>
 *   <li>FAIL_AFTER models the progressive slab allocator exhaustion threshold: glibc's spawn helper
 *       and the kernel have free memory for N spawn's internal structures; the (N+1)th spawn fails;
 *       assert that the application detects this threshold and escalates to a memory-pressure alert.</li>
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       post-threshold ENOMEM — POSIX does not define the pid value when spawn fails; the calling
 *       process is in a clean state and no child resources need to be reaped.</li>
 * </ul>
 * Production failure mode: a service uses {@code posix_spawnp} to invoke utilities by name during
 * batch processing; the Kubernetes node is under memory pressure from OOM-protected workloads;
 * after N successful spawns glibc's spawn helper cannot allocate the internal communication pipe;
 * the service applies the same short retry interval as for EAGAIN, which is insufficient for the
 * duration of the memory pressure event and worsens node OOM conditions.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER models the kernel slab exhaustion threshold: the slab allocator's free lists drain
 * as each spawn allocates task_struct, kernel stack, mm_struct, and glibc's internal pipe
 * buffers; after N spawns the allocator is exhausted and all subsequent spawns fail with ENOMEM.
 * POSIX spawn returns the error code directly — checking {@code if (ret < 0)} silently misses
 * ENOMEM (12). ENOMEM (12) and EAGAIN (11) are adjacent integers; misclassification between them
 * causes wrong retry behaviour — EAGAIN calls for short exponential backoff; ENOMEM calls for
 * longer backoff with memory pressure alert.
 *
 * <p>The posix_spawnp PATH traversal adds a subtle memory pressure contribution not present in
 * posix_spawn: each PATH directory is opened with {@code opendir}, which allocates a
 * {@code DIR} structure and read buffer in the parent process's heap. Under extreme memory
 * pressure, this PATH traversal allocation itself may fail with ENOMEM before the fork phase.
 * The interposer fires at the spawn API boundary and covers both the traversal-allocation and
 * the spawn-internal-structure-allocation failure cases.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: the first test method exercises the success path (calls 1 through N);
 * subsequent test methods exercise the ENOMEM path automatically. ENOMEM from posix_spawnp leaves
 * the calling process in a clean state — no child resources were allocated, no waitpid is needed.
 * Retry strategy must be substantially longer than for EAGAIN: ENOMEM may persist for minutes
 * during node-level OOM reclaim, whereas EAGAIN self-heals when children exit.
 *
 * <p>Under real node OOM conditions, the kernel OOM killer may terminate processes concurrently
 * with the application's retries. Applications retrying posix_spawnp-ENOMEM should monitor for
 * SIGCHLD from killed children and avoid accumulating zombie processes during the retry window.
 * If the application itself is OOM-killed during the ENOMEM phase, the retry logic is moot —
 * the memory alert must be raised before the OOM killer activates.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEnomemFailAfter(successesBeforeFailure = 30)
 * class PosixSpawnpMemoryExhaustionTest {
 *   @Test
 *   void executorAppliesLongerBackoffOnEnomemThanEagainAndRaisesMemoryAlert(ConnectionInfo info) {
 *     // first 30 spawns succeed; subsequent spawns return ENOMEM;
 *     // verify longer backoff than EAGAIN; memory alert escalated; no waitpid on uninit pid;
 *     // return value checked (not errno); ENOMEM vs EAGAIN distinguished
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of spawns before the node's slab memory is exhausted; values 10–100 cover most scenarios;
 * 0 tests cold-start memory exhaustion (node already OOM at test start).
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnpEnomemFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.ENOMEM)
public @interface ChaosPosixSpawnpEnomemFailAfter {

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
   * @ChaosPosixSpawnpEnomemFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnpEnomemFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnpEnomemFailAfter[] value();
  }
}
