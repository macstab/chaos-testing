/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawn;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessLatencyBinding;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Delays every {@code posix_spawn} call intercepted by libchaos-process by {@link #delayMs}
 * milliseconds before delegating to the real kernel call, causing the calling code to observe a
 * slow spawn without receiving an error.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, effect = LATENCY) pair.
 * Unlike ERRNO variants, the LATENCY primitive always delegates to the real kernel call — it only
 * injects wall-clock cost before issuing the spawn. The spawn succeeds (or fails for genuine
 * reasons); only the time taken increases. Compile-time safety: invalid selector/effect combinations
 * have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code posix_spawn} call the interposer sleeps for {@link #delayMs} milliseconds
 *       in the calling thread before issuing the real spawn; the calling thread is stalled for the
 *       full delay period before the spawn's internal fork+exec sequence begins.</li>
 *   <li>After the sleep the real {@code posix_spawn} call proceeds normally through its internal
 *       fork, file-actions, and exec sequence.</li>
 *   <li>The calling code receives: the real spawn return value (0 on success, or an error code),
 *       after a wall-clock delay of at least {@link #delayMs} ms; no spurious error code is
 *       injected; the spawn either succeeds or fails for a genuine kernel reason.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>Every {@code posix_spawn} call takes at least {@link #delayMs} ms longer than baseline;
 *       assert that request timeouts that include a spawn call are calibrated to include the spawn
 *       latency plus the child's processing time — a fixed timeout measured from request arrival
 *       will fire before the spawn completes if the delay exceeds the slack in the budget.</li>
 *   <li>Applications that use {@code posix_spawn} for on-demand subprocess creation (executing
 *       shell commands, launching helper tools) must not set their subprocess-creation deadline
 *       as an absolute wall-clock timeout measured from the spawn call start — assert that the
 *       timeout is relative to the child's readiness signal (stdout close, exit code) rather than
 *       relative to the spawn invocation.</li>
 *   <li>Assert that concurrent spawn calls under latency injection do not exceed the process's
 *       fd budget: the glibc spawn implementation opens internal pipes for parent-child error
 *       communication; if many spawns are in-flight simultaneously (each stalled in the interposer
 *       sleep), the accumulated open pipes may approach {@code RLIMIT_NOFILE}.</li>
 * </ul>
 * Production failure mode: a pipeline tool uses {@code posix_spawn} to launch a sequence of
 * subprocess commands; each command spawn has a 500ms deadline; the node is under scheduling
 * pressure and each spawn stalls for 200ms in the interposer (simulating kernel scheduler delay);
 * commands that include non-trivial work leave only 300ms for execution; the pipeline timer fires
 * premature timeouts on commands that would have completed in 450ms, producing spurious failures
 * that are attributed to the subprocess rather than to spawn latency.
 *
 * <h2>Deep technical dive</h2>
 * <p>The LATENCY primitive injects delay before the {@code posix_spawn} API call, simulating
 * scheduling stalls in the userspace-to-kernel transition. The actual spawn operation (fork,
 * file-actions, exec) runs at kernel speed after the sleep. This models the scenario where the
 * application's request to create a subprocess is delayed by CPU saturation or memory reclaim
 * on the node, not by a slow kernel spawn implementation.
 *
 * <p>The {@code posix_spawn} API is synchronous: it blocks in the parent until the child has
 * either successfully exec'd the target binary or reported an error. This means the full
 * spawn-to-child-ready latency is: interposer sleep + internal fork time + exec time +
 * child startup time. Under latency injection, the interposer sleep dominates for the first
 * component, but the remaining components (which are not injected) add to the total. Applications
 * must account for the full chain when setting spawn-related timeouts.
 *
 * <p>Concurrent spawn calls under latency injection accumulate glibc's internal pipe fds: each
 * in-flight spawn holds two fds (the parent's end of the error-reporting pipe) for the duration
 * of the spawn. Under high concurrency with a large delay, many spawns may be simultaneously
 * stalled in the interposer sleep, each holding two open pipe fds. If the total exceeds
 * {@code RLIMIT_NOFILE / 2}, the next spawn's pipe allocation fails with EMFILE. Applications
 * that spawn in parallel must implement a concurrency limit.
 *
 * <p>Subprocess-based pipeline tools that chain {@code posix_spawn} calls must account for spawn
 * latency in their end-to-end pipeline budget. A pipeline of N stages, each with spawn latency L,
 * adds N × L to the pipeline's minimum wall-clock time before the first stage even begins
 * processing. Tools that measure pipeline throughput from the first spawn call start will
 * underestimate the spawn overhead in high-latency environments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnLatency(delayMs = 150)
 * class PosixSpawnSchedulingStallTest {
 *   @Test
 *   void pipelineTimeoutsRelativeToChildReadinessNotSpawnCallStart(ConnectionInfo info) {
 *     // verify spawn completes despite 150ms delay; timeout relative to child readiness signal;
 *     // no premature timeout on spawn call; concurrent spawn concurrency within fd budget
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 50–200ms simulates realistic scheduling stalls; values above
 * the application's subprocess-creation deadline expose timeout calibration gaps; 500ms+ simulates
 * heavily loaded nodes; combine with concurrent spawns to surface fd-exhaustion risks from
 * accumulated internal pipes.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessLatencyBinding
 * @see com.macstab.chaos.process.model.ProcessRule#latency(ProcessSelector, java.time.Duration)
 */
@Repeatable(ChaosPosixSpawnLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessLatencyTranslator")
@ProcessLatencyBinding(selector = ProcessSelector.POSIX_SPAWN)
public @interface ChaosPosixSpawnLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 100L;

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
   * @ChaosPosixSpawnLatency(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnLatency(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnLatency[] value();
  }
}
