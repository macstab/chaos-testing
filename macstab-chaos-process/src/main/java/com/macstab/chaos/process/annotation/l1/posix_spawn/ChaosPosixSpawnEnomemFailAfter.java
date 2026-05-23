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
 * After {@link #successesBeforeFailure} successful {@code posix_spawn} calls, injects
 * {@code ENOMEM} on every subsequent call, causing the calling code to observe an out-of-memory
 * failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code ENOMEM},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed,
 * then the counter trips permanently and every subsequent call returns the error code until the
 * rule is removed. Compile-time safety: invalid selector/errno/effect combinations have no
 * annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code posix_spawn}
 *       call returns {@code ENOMEM} directly (POSIX spawn returns the error code, not -1).</li>
 *   <li>The calling code receives: return value {@code ENOMEM} (12); no child process is created;
 *       the calling process is in a clean state since no child resources were allocated.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code ENOMEM}; assert that the application applies backoff with a longer interval
 *       than for EAGAIN — ENOMEM may persist for the duration of a node memory pressure event.</li>
 *   <li>FAIL_AFTER models the progressive kernel memory exhaustion threshold: the slab allocator
 *       has capacity for N spawn's internal structures; the (N+1)th spawn fails; assert that the
 *       application detects this threshold and escalates to a memory-pressure alert.</li>
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       post-threshold ENOMEM — POSIX does not define the pid value when spawn fails.</li>
 * </ul>
 * Production failure mode: a job runner uses {@code posix_spawn} to launch worker processes;
 * the Kubernetes node is under memory pressure from OOM-protected workloads; after N successful
 * spawns the kernel slab allocator cannot satisfy the spawn's internal structure allocation;
 * the runner applies the same short retry interval as for EAGAIN, which is insufficient for the
 * duration of the memory pressure event, causing repeated failed retries that worsen the load.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER models the kernel slab exhaustion threshold: the slab allocator's free lists
 * drain as the system creates more spawn-internal structures; after N spawns the allocator is
 * exhausted and all subsequent spawns fail with ENOMEM. POSIX spawn returns the error code
 * directly — checking {@code if (ret < 0)} misses ENOMEM (12). ENOMEM from posix_spawn leaves
 * the calling process in a clean state (no child resources allocated). Retry strategy must be
 * longer than for EAGAIN: ENOMEM may persist for minutes during node-level OOM reclaim, while
 * EAGAIN self-heals when children exit. The counter does not reset at class scope, enabling
 * sequential testing of the success and ENOMEM-with-longer-backoff phases.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEnomemFailAfter(successesBeforeFailure = 30)
 * class PosixSpawnMemoryExhaustionTest {
 *   @Test
 *   void runnerAppliesLongerBackoffOnEnomemThanEagainAfterThreshold(ConnectionInfo info) {
 *     // first 30 spawns succeed; subsequent spawns return ENOMEM;
 *     // verify memory alert raised; backoff longer than EAGAIN; no waitpid on uninit pid
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of spawns before the node's slab memory is exhausted; values 10–100 cover most scenarios;
 * 0 tests cold-start memory exhaustion.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosPosixSpawnEnomemFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.ENOMEM)
public @interface ChaosPosixSpawnEnomemFailAfter {

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
   * @ChaosPosixSpawnEnomemFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosPosixSpawnEnomemFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosPosixSpawnEnomemFailAfter[] value();
  }
}
