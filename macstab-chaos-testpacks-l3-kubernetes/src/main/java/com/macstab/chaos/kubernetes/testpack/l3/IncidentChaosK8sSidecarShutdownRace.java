/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kubernetes.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates the Kubernetes sidecar shutdown race condition: Kubernetes sends SIGTERM to all
 * containers in a pod simultaneously. The Envoy or istio-proxy sidecar closes its listeners
 * before the application container has finished draining, causing all outbound calls made during
 * the drain window to receive ECONNREFUSED.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: CONNECT → ECONNREFUSED at {@code toxicity} — models outbound connections
 *       refused by the already-closed Envoy/istio-proxy sidecar listener
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>Severe</strong><br>5–15% of requests fail during pod shutdown; the failure
 * window affects every rolling deploy and every HPA scale-down event.
 *
 * <h2>Industry references</h2>
 * <p>Istio and Envoy documentation acknowledge the simultaneous SIGTERM as a known race. Multiple
 * Kubernetes service mesh post-mortems describe ECONNREFUSED spikes during deploys that correlate
 * exactly with sidecar termination order.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosK8sSidecarShutdownRace(toxicity = 0.5)
 * class SidecarShutdownRaceTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosK8sSidecarShutdownRace.List.class)
@ChaosL3(composer = "com.macstab.chaos.kubernetes.testpack.l3.composers.K8sSidecarShutdownRaceComposer", severity = Severity.SEVERE)
public @interface IncidentChaosK8sSidecarShutdownRace {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** Fraction of CONNECT syscalls that return ECONNREFUSED (0.0–1.0). */
    double toxicity() default 0.5;

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosK8sSidecarShutdownRace[] value();
    }
}
