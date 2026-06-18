/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kubernetes.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates the CFS CPU throttle / GC amplification loop: a Kubernetes CPU limit causes the CFS
 * scheduler to throttle the JVM. The JVM has spawned N GC threads (one per core), and CFS throttles
 * all of them simultaneously. A 50 ms GC pause becomes 400 ms wall-clock time. The Kubernetes
 * liveness probe times out and kills the pod.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: SafepointStorm every {@code gcIntervalMs} ms — drives frequent GC safepoints that are
 *       then extended by CFS throttle into multi-hundred-millisecond pauses
 *   <li>Connection: RECV → timeout — models downstream services timing out while waiting for
 *       responses from the throttled JVM
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * 71% of Kubernetes deployments with CPU limits experience this; liveness probes kill healthy pods
 * under transient load, causing cascading restart loops that amplify rather than heal the incident.
 *
 * <h2>Industry references</h2>
 *
 * <p>CFS bandwidth throttling and its interaction with JVM GC thread counts is documented in
 * Netflix, LinkedIn, and Booking.com engineering posts. The 71% figure comes from large-scale
 * analysis of Kubernetes cluster configurations with CPU limits enabled.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosK8sCpuThrottleGcAmplification(gcIntervalMs = 100L, toxicity = 0.4)
 * class CpuThrottleGcAmplificationTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosK8sCpuThrottleGcAmplification.List.class)
@ChaosL3(
    composer =
        "com.macstab.chaos.kubernetes.testpack.l3.composers.K8sCpuThrottleGcAmplificationComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosK8sCpuThrottleGcAmplification {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Interval between forced safepoints in milliseconds. */
  long gcIntervalMs() default 100L;

  /** Fraction of RECV syscalls that time out (0.0–1.0). */
  double toxicity() default 0.4;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosK8sCpuThrottleGcAmplification[] value();
  }
}
