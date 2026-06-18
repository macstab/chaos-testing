/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kubernetes.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;
import com.macstab.chaos.kubernetes.testpack.l3.IncidentChaosK8sDnsNdots5Storm;

/**
 * Composer for {@link IncidentChaosK8sDnsNdots5Storm}.
 *
 * <p>Injects EAI_AGAIN transient failures and high DNS latency to reproduce the CoreDNS overload
 * caused by the Kubernetes ndots:5 default that forces four NXDOMAIN search-domain queries per
 * external hostname lookup.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class K8sDnsNdots5StormComposer implements L3Composer<IncidentChaosK8sDnsNdots5Storm> {

  public K8sDnsNdots5StormComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosK8sDnsNdots5Storm ann) {
    final List<Object> handles = new ArrayList<>();
    final var dns = CompositeDnsChaos.standard().advanced();
    handles.add(dns.apply(container, DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN)));
    handles.add(
        dns.apply(
            container,
            DnsRule.latency(DnsSelector.anyForward(), Duration.ofMillis(ann.latencyMs()))));
    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosK8sDnsNdots5Storm ann) {
    return List.of(
        "K8s DNS ndots:5 Storm — 4 NXDOMAIN search attempts overwhelm CoreDNS",
        "dns: EAI_AGAIN + latency=" + ann.latencyMs() + "ms",
        "severity=SEVERE — every external call takes 5–20s until CoreDNS autoscales");
  }
}
