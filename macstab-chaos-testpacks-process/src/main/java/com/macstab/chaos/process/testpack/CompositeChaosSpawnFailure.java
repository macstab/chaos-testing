/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Every {@code posix_spawn()} call fails with {@code ENOMEM}, simulating the kernel failing to
 * allocate memory for the new process during a spawn operation. {@code posix_spawn} is the
 * preferred process-creation primitive in constrained environments because it avoids the
 * copy-on-write overhead of fork; when it returns {@code ENOMEM}, the application cannot launch
 * child processes at all — shell-out helpers, subprocess-based feature implementations, and
 * one-shot job dispatch all fail.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.POSIX_SPAWN, ProcessErrno.ENOMEM, toxicity)}
 * via libchaos-process. In production this happens during memory-pressure events on nodes running
 * many containers, or when a container's memory cgroup limit is nearly exhausted and the kernel
 * cannot satisfy the allocation requests required for a new task_struct and its associated kernel
 * stack.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * At {@code toxicity = 0.5} half of spawn attempts fail. Features relying on subprocess execution
 * degrade — some succeed, some fail — creating inconsistent behaviour that is harder to detect than
 * a total failure. Applications without per-feature circuit breakers may accumulate spawned but
 * failed tasks silently, leading to resource leaks.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code ENOMEM} from {@code posix_spawn} is documented in the POSIX.1-2017 specification
 * (volume: System Interfaces, {@code posix_spawn}). The pattern of {@code posix_spawn} replacing
 * {@code fork}/{@code exec} pairs in performance-sensitive applications is described in the glibc
 * manual and in the FreeBSD performance tuning guide.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosSpawnFailure(toxicity = 0.5)
 * class SpawnFailureTest {
 *   @Test
 *   void spawnEnomemIsReportedAndJobIsRetried() {
 *     // assert: ENOMEM propagated to caller; job queued for retry; no silent discard
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosSpawnFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.SpawnFailureComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosSpawnFailure {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code ENOMEM} fires on each {@code posix_spawn()} call.
   * Defaults to {@code 0.5} (half of spawn attempts fail).
   */
  double toxicity() default 0.5;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosSpawnFailure[] value();
  }
}
