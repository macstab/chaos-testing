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
 * <p>Every {@code waitpid()} call is delayed by the configured drain period before returning,
 * simulating a child process that takes a long time to complete its graceful shutdown. Process
 * supervisors and container runtimes that implement graceful-shutdown sequences depend on
 * {@code waitpid} completing within a timeout budget; if the drain period exceeds that budget,
 * the runtime escalates to {@code SIGKILL} — potentially interrupting in-flight request handling
 * and causing data loss.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.latency(ProcessSelector.WAITPID, drainMs)} via libchaos-process.
 * In production this happens when a worker process holds a database transaction open during
 * shutdown, when a JVM finaliser thread delays exit, or when a signal handler performs network
 * I/O (flushing an audit log to a remote service) before calling {@code exit}.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * The service handles the delay without data loss if its shutdown timeout is longer than
 * {@code drainMs}. The risk materialises when orchestrators (Kubernetes terminationGracePeriodSeconds,
 * Docker stop-timeout) enforce a shorter timeout — then the escalation to SIGKILL interrupts
 * cleanup and may leave resources (locks, temp files, open transactions) in an inconsistent state.
 *
 * <h2>Industry references</h2>
 *
 * <p>Graceful-shutdown drain periods and {@code waitpid} interaction are discussed in the
 * Kubernetes documentation on pod lifecycle and {@code terminationGracePeriodSeconds}.
 * The pattern of JVM shutdown hooks delaying exit is a common cause of {@code SIGKILL}
 * escalation in containerised Java deployments, documented in multiple production post-mortems
 * shared in the Java community.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosGracefulShutdown(drainMs = 5000)
 * class GracefulShutdownTest {
 *   @Test
 *   void shutdownCompletesWithinGracePeriodWithoutDataLoss() {
 *     // assert: in-flight requests completed; no SIGKILL escalation; resources released cleanly
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosGracefulShutdown.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.GracefulShutdownComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosGracefulShutdown {

  /**
   * Delay added to each {@code waitpid()} call in milliseconds. Defaults to {@code 5000}
   * (5 seconds) — a typical graceful-drain budget for a containerised service.
   */
  long drainMs() default 5_000L;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosGracefulShutdown[] value();
  }
}
