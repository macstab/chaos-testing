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
 * <h2>What this is</h2>
 *
 * <p>Simulates the resource-starvation outcome of an uncontrolled process-creation burst by
 * returning {@code EAGAIN} from {@code fork()} at very high probability. Unlike a real fork bomb,
 * which exhausts the process table by spawning processes, this scenario tests the application's
 * resilience to the result — a kernel that refuses to create more processes — without actually
 * endangering the host. Applications that do not implement fork-rate limiting or process-slot
 * accounting will observe cascading failures as workers cannot be spawned.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN, toxicity)} via
 * libchaos-process with a very high probability (default {@code 0.95}). In production this
 * corresponds to the state reached after a fork bomb has saturated the node's process table or
 * {@code RLIMIT_NPROC} ceiling — any further fork call returns {@code EAGAIN} regardless of which
 * process attempts it, because the uid slot is exhausted.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * At {@code toxicity = 0.95} virtually all fork attempts fail. Any application feature that relies
 * on process creation is unavailable. Connection handling, credential isolation, and job dispatch
 * subsystems fail in concert. The node requires intervention to clear the process table before
 * normal operation resumes. Data loss is possible if in-flight requests that required worker
 * processes are dropped.
 *
 * <h2>Industry references</h2>
 *
 * <p>Fork-bomb mitigation and process-limit enforcement are documented in the {@code
 * RLIMIT_NPROC} section of the Linux {@code setrlimit(2)} man-page and in the cgroups v2
 * {@code pids.max} controller documentation. Container runtimes enforce a per-pod process limit
 * via {@code pids.max} to prevent exactly this failure mode; this scenario tests the application's
 * tolerance to the post-saturation steady state without triggering cgroups OOM.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosForkBomb(toxicity = 0.95)
 * class ForkBombResilienceTest {
 *   @Test
 *   void serviceDegradesGracefullyUnderProcessStarvation() {
 *     // assert: degraded-mode response returned; no deadlock; alerted via metric
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosForkBomb.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.ForkBombComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosForkBomb {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code EAGAIN} fires on each {@code fork()} call.
   * Defaults to {@code 0.95} (nineteen out of twenty fork attempts fail).
   */
  double toxicity() default 0.95;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosForkBomb[] value();
  }
}
