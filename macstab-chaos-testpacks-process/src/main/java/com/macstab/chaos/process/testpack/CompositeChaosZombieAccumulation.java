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
 * <p>Every {@code waitpid()} call fails with {@code ECHILD} at the configured probability,
 * simulating the kernel reporting that the calling process has no children whose status can be
 * collected. Applications that fork child processes and then call {@code waitpid} to reap them
 * will believe all children have already exited — even if they have not. In practice this causes
 * the parent to stop reaping children, accumulating zombie processes until the process table
 * fills and no new processes can be created.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.WAITPID, ProcessErrno.ECHILD, toxicity)}
 * via libchaos-process. In production {@code ECHILD} occurs when a signal handler calls
 * {@code waitpid} on a PID that another handler already reaped (double-reap), when {@code
 * SIGCHLD} is set to {@code SIG_IGN} (which causes the kernel to auto-reap, making subsequent
 * explicit waits return {@code ECHILD}), or in pid-namespace scenarios where a process waits for
 * a pid it did not directly create.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * Zombie accumulation is a slow-burn failure: the process table fills gradually. At {@code
 * toxicity = 0.7} most wait calls return {@code ECHILD}, leaving the majority of children
 * un-reaped. The service remains functional until the process table is full, after which no
 * new processes can be forked. Well-implemented supervisors track children independently of
 * wait-call success and will detect the mismatch.
 *
 * <h2>Industry references</h2>
 *
 * <p>Zombie process accumulation and the role of {@code SIGCHLD}/{@code waitpid} in preventing
 * it are documented in the Linux {@code wait(2)} man-page and in the Docker documentation on
 * init processes inside containers. The Kubernetes recommendation to use an init process (such
 * as {@code tini}) inside containers specifically addresses the zombie-reaping responsibility.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosZombieAccumulation(toxicity = 0.7)
 * class ZombieAccumulationTest {
 *   @Test
 *   void supervisorDetectsUnreapedChildrenAndEscalates() {
 *     // assert: zombie count metric incremented; alert triggered before process table fills
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosZombieAccumulation.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.ZombieAccumulationComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosZombieAccumulation {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code ECHILD} fires on each {@code waitpid()} call.
   * Defaults to {@code 0.7} (seven in ten wait calls return no-child, leaving children un-reaped).
   */
  double toxicity() default 0.7;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosZombieAccumulation[] value();
  }
}
