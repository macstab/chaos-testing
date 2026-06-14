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
 * <p>Simulates the scenario where G1 GC temporarily exceeds the configured {@code -Xmx} during heap
 * evacuation, pushing RSS above the cgroup memory limit. The kernel OOM killer terminates the JVM
 * with exit code 137 mid-GC — no Java {@code OutOfMemoryError} appears in logs.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Memory: OOM-kill at {@code toxicity} — simulates the cgroup RSS limit breach that triggers
 *       the kernel OOM killer during a G1 evacuation pause
 *   <li>JVM: {@code OutOfMemoryError} injection on application classes — models the heap exhaustion
 *       visible to application code just before the OS-level kill arrives
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * The pod is killed with no Java OOM in logs; GC log shows "GC overhead limit exceeded" then
 * silence. Engineers waste hours looking for a Java-level root cause that does not exist.
 *
 * <h2>Industry references</h2>
 *
 * <p>G1 heap evacuation failure causing cgroup RSS breach is documented in multiple JVM and
 * Kubernetes operator post-mortems. The symptom — exit 137 with no Java stack trace — is a
 * well-known diagnostic gap in containerised JVM deployments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosK8sOomKillMidGc(toxicity = 0.8)
 * class OomKillMidGcTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosK8sOomKillMidGc.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.kubernetes.testpack.l3.composers.K8sOomKillMidGcComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosK8sOomKillMidGc {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Probability (0.0–1.0) that the OOM killer fires on any given allocation. */
  double toxicity() default 0.8;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosK8sOomKillMidGc[] value();
  }
}
