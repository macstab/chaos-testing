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
 * <p>Every {@code fork()} call fails with {@code EAGAIN} at the configured probability, simulating
 * a kernel process-table slot or {@code RLIMIT_NPROC} exhaustion. From the application's perspective
 * the OS is refusing to create new processes: server-side workers cannot be spawned, CGI-style
 * process-per-request servers silently drop requests, and credential-isolation daemons that fork for
 * privilege separation return error to the caller.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN, toxicity)} via
 * libchaos-process. In production this happens when a node runs too many processes — a container
 * runtime or sidecar process burst can push the uid process count to {@code RLIMIT_NPROC} and
 * cause every subsequent fork on any container sharing that uid to fail.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * At {@code toxicity = 0.9} nine out of ten fork attempts fail. Applications that treat
 * fork-EAGAIN as a hard error (vs. retrying with backoff) will immediately begin returning
 * errors to callers. Without a process-table health check in readiness probes the service
 * appears healthy to the scheduler while silently rejecting work. Manual intervention or
 * node-level process-count monitoring is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code RLIMIT_NPROC} as a source of {@code EAGAIN} from {@code fork} is documented in the
 * Linux {@code fork(2)} and {@code setrlimit(2)} man-pages. The pattern of containerised services
 * sharing a uid and exhausting the per-uid process limit is described in Kubernetes best-practice
 * guides recommending distinct UIDs per pod and {@code PodSecurity} admission enforcement.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosProcessLimitHit(toxicity = 0.9)
 * class ProcessLimitResilienceTest {
 *   @Test
 *   void workerSpawnFailureIsHandledGracefully() {
 *     // assert: no silent request drop; fork errors reported via metrics; backoff applied
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosProcessLimitHit.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.ProcessLimitHitComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosProcessLimitHit {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code EAGAIN} fires on each {@code fork()} call.
   * Defaults to {@code 0.9} (nine in ten fork attempts fail).
   */
  double toxicity() default 0.9;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosProcessLimitHit[] value();
  }
}
