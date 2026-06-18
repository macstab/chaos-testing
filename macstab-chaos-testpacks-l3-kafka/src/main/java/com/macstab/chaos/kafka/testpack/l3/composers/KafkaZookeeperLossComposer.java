/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;
import com.macstab.chaos.kafka.testpack.l3.IncidentChaosKafkaZookeeperLoss;

/**
 * Composer for {@link IncidentChaosKafkaZookeeperLoss}.
 *
 * <p>Applies DNS EAI_FAIL on all forward lookups and connection ECONNREFUSED to reproduce the
 * compound failure profile of ZooKeeper metadata service loss with Kafka producers blocking
 * indefinitely on missing metadata.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class KafkaZookeeperLossComposer
    implements L3Composer<IncidentChaosKafkaZookeeperLoss> {

  public KafkaZookeeperLossComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosKafkaZookeeperLoss ann) {
    final List<Object> handles = new ArrayList<>();

    final var dns = CompositeDnsChaos.standard().advanced();
    handles.add(dns.apply(container, DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_FAIL)));

    final var conn = CompositeConnectionChaos.standard().advanced();
    handles.add(
        conn.apply(
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
  public List<String> describe(final IncidentChaosKafkaZookeeperLoss ann) {
    return List.of(
        "Kafka ZooKeeper Loss — metadata service unreachable",
        "dns: EAI_FAIL on all forward lookups",
        "connection: CONNECT ECONNREFUSED toxicity=" + ann.toxicity(),
        "severity=CRITICAL — producers block indefinitely; no metadata available");
  }
}
