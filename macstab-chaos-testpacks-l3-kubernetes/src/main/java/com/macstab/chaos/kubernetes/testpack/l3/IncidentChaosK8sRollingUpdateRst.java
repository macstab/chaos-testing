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
 * <p>Simulates the iptables endpoint removal lag that occurs during every Kubernetes rolling
 * update: old pod IPs stay in iptables rules for ~30 seconds after the pod terminates, causing TCP
 * RST on all in-flight requests that were routed to the terminating pod.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV → ECONNRESET at {@code toxicity} — models the TCP RST injected by the
 *       kernel when packets arrive at a socket that has already been torn down
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * 73% of Slack production incidents originate from deploys; iptables rules propagate with
 * approximately 30 seconds of lag, meaning every rolling deploy is a silent RST window.
 *
 * <h2>Industry references</h2>
 *
 * <p>Slack engineering blog: 73% of incidents traced to deploy events. Kubernetes networking
 * documentation acknowledges iptables propagation lag as a known source of connection resets during
 * rolling updates.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosK8sRollingUpdateRst(toxicity = 0.3)
 * class RollingUpdateRstTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosK8sRollingUpdateRst.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.kubernetes.testpack.l3.composers.K8sRollingUpdateRstComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosK8sRollingUpdateRst {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Fraction of RECV syscalls that return ECONNRESET (0.0–1.0). */
  double toxicity() default 0.3;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosK8sRollingUpdateRst[] value();
  }
}
