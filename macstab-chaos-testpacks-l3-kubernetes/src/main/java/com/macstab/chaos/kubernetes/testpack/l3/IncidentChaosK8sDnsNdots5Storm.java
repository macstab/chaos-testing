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
 * <p>Simulates the Kubernetes ndots:5 DNS storm: every external hostname lookup triggers four
 * NXDOMAIN search-domain queries before the resolver attempts the bare name. Under load this
 * overwhelms CoreDNS, producing 5–20 second DNS timeouts (tracked as kubernetes/kubernetes#56903).
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>DNS: EAI_AGAIN on every forward lookup — models the transient NXDOMAIN/SERVFAIL responses
 *       from an overloaded CoreDNS under ndots:5 amplification
 *   <li>DNS: latency of {@code latencyMs} ms on every forward lookup — models the 5–20 second
 *       timeout window before CoreDNS autoscales
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Every external HTTP/gRPC call takes 5–20 seconds until CoreDNS autoscales; connection pools drain
 * and thread pools fill with waiting callers.
 *
 * <h2>Industry references</h2>
 *
 * <p>kubernetes/kubernetes issue #56903 documents the ndots:5 default as a known source of DNS
 * amplification. Weave, Cloudflare, and multiple Kubernetes operators have published post-mortems
 * tracing production outages to this behaviour.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosK8sDnsNdots5Storm(latencyMs = 5000L)
 * class DnsNdots5StormTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosK8sDnsNdots5Storm.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.kubernetes.testpack.l3.composers.K8sDnsNdots5StormComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosK8sDnsNdots5Storm {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** DNS latency in milliseconds to inject on every forward lookup. */
  long latencyMs() default 5000L;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosK8sDnsNdots5Storm[] value();
  }
}
