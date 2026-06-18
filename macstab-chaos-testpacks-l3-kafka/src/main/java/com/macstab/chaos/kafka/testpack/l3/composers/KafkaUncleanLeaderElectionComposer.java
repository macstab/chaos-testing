/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.kafka.testpack.l3.IncidentChaosKafkaUncleanLeaderElection;

/**
 * Composer for {@link IncidentChaosKafkaUncleanLeaderElection}.
 *
 * <p>Applies high RECV latency to simulate ISR replication lag and injects TimeoutException at
 * METHOD_EXIT to reproduce the compound failure profile of an unclean leader election where a
 * lagged broker is promoted, causing message loss and consumer timestamp regression.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class KafkaUncleanLeaderElectionComposer
    implements L3Composer<IncidentChaosKafkaUncleanLeaderElection> {

  public KafkaUncleanLeaderElectionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosKafkaUncleanLeaderElection ann) {
    final List<Object> handles = new ArrayList<>();

    final var conn = CompositeConnectionChaos.standard().advanced();
    handles.add(
        conn.apply(
            container,
            NetRule.latency(
                Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

    final String scenarioId =
        JvmPlanAccumulator.instance().mintScenarioId("KafkaUncleanLeaderElection");
    final NamePattern cls =
        ann.classPattern().isBlank() ? NamePattern.any() : NamePattern.prefix(ann.classPattern());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description("Kafka unclean leader election — ISR lag, lagged broker promoted")
            .selector(
                ChaosSelector.method(EnumSet.of(OperationType.METHOD_EXIT), cls, NamePattern.any()))
            .effect(
                ChaosEffect.injectException(
                    "org.apache.kafka.common.errors.TimeoutException",
                    "Kafka unclean leader election — lagged broker promoted, messages lost"))
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
  public List<String> describe(final IncidentChaosKafkaUncleanLeaderElection ann) {
    return List.of(
        "Kafka Unclean Leader Election — lagged broker promoted to leader",
        "connection: RECV latency " + ann.latencyMs() + "ms (ISR replication lag)",
        "jvm: TimeoutException on class prefix '" + ann.classPattern() + "' (METHOD_EXIT)",
        "severity=CRITICAL — 500+ messages permanently lost; consumer timestamp regression");
  }
}
