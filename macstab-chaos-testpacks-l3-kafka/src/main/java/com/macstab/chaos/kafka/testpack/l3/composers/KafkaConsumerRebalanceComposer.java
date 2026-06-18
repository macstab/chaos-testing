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
import com.macstab.chaos.kafka.testpack.l3.IncidentChaosKafkaConsumerRebalance;

/**
 * Composer for {@link IncidentChaosKafkaConsumerRebalance}.
 *
 * <p>Injects a GC-pause simulation via a JVM RuntimeException and adds RECV latency to reproduce
 * the broker-side symptom of a consumer that stops sending heartbeats during a long pause, causing
 * the group coordinator to trigger a consumer group rebalance.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class KafkaConsumerRebalanceComposer
    implements L3Composer<IncidentChaosKafkaConsumerRebalance> {

  public KafkaConsumerRebalanceComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosKafkaConsumerRebalance ann) {
    final List<Object> handles = new ArrayList<>();

    final String scenarioId =
        JvmPlanAccumulator.instance().mintScenarioId("KafkaConsumerRebalance");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description("simulated GC pause — poll deadline exceeded")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.lang.RuntimeException", "simulated GC pause — poll deadline exceeded"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

    final var conn = CompositeConnectionChaos.standard().advanced();
    handles.add(
        conn.apply(
            container,
            NetRule.latency(
                Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.gcPauseMs()), 1.0)));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosKafkaConsumerRebalance ann) {
    return List.of(
        "Kafka Consumer Rebalance — GC pause exceeds max.poll.interval.ms",
        "jvm: RuntimeException on class prefix '" + ann.classPattern() + "' (METHOD_ENTER)",
        "connection: RECV latency " + ann.gcPauseMs() + "ms (heartbeat starvation)",
        "severity=SEVERE — partition reassignment and potential duplicate processing");
  }
}
