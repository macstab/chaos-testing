/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3.composers;

import java.util.ArrayList;
import java.util.EnumSet;
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
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.kafka.testpack.l3.IncidentChaosKafkaBrokerFailure;

/**
 * Composer for {@link IncidentChaosKafkaBrokerFailure}.
 *
 * <p>Applies connection ECONNREFUSED, transient DNS EAI_AGAIN, and a JVM TimeoutException to
 * reproduce the compound failure profile of a Kafka broker going down mid-operation with producers
 * and consumers in an active retry storm.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class KafkaBrokerFailureComposer
    implements L3Composer<IncidentChaosKafkaBrokerFailure> {

  public KafkaBrokerFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosKafkaBrokerFailure ann) {
    final List<Object> handles = new ArrayList<>();

    final var conn = CompositeConnectionChaos.standard().advanced();
    handles.add(
        conn.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED, ann.toxicity())));

    final var dns = CompositeDnsChaos.standard().advanced();
    handles.add(dns.apply(container, DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN)));

    final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("KafkaBrokerFailure");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description("Kafka broker unreachable — producer retry storm")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "org.apache.kafka.common.errors.TimeoutException",
                    "Kafka broker unreachable — producer retry storm"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosKafkaBrokerFailure ann) {
    return List.of(
        "Kafka Broker Failure — broker down with producer/consumer retry storm",
        "connection: CONNECT → ECONNREFUSED, toxicity=" + ann.toxicity(),
        "dns: EAI_AGAIN on every forward lookup",
        "jvm: TimeoutException on class prefix '" + ann.classPattern() + "' (METHOD_ENTER)",
        "severity=CRITICAL — full producer/consumer connectivity loss");
  }
}
