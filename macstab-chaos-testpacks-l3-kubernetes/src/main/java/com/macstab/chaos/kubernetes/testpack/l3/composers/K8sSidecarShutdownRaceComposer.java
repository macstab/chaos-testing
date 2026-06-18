/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kubernetes.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.kubernetes.testpack.l3.IncidentChaosK8sSidecarShutdownRace;

/**
 * Composer for {@link IncidentChaosK8sSidecarShutdownRace}.
 *
 * <p>Injects CONNECT ECONNREFUSED at the configured toxicity to reproduce the outbound connection
 * failures caused by Envoy/istio-proxy closing its listeners before the application container has
 * finished draining during pod shutdown.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class K8sSidecarShutdownRaceComposer
    implements L3Composer<IncidentChaosK8sSidecarShutdownRace> {

  public K8sSidecarShutdownRaceComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosK8sSidecarShutdownRace ann) {
    final List<Object> handles = new ArrayList<>();
    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED, ann.toxicity())));
    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosK8sSidecarShutdownRace ann) {
    return List.of(
        "K8s Sidecar Shutdown Race — Envoy/istio-proxy ECONNREFUSED before app drains",
        "connection: CONNECT ECONNREFUSED toxicity=" + ann.toxicity(),
        "severity=SEVERE — 5–15% of requests fail during pod shutdown; affects every deploy");
  }
}
