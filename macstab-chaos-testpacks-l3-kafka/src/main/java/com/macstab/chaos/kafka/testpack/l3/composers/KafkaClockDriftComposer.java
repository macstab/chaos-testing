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
import com.macstab.chaos.kafka.testpack.l3.IncidentChaosKafkaClockDrift;
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeRule;

/**
 * Composer for {@link IncidentChaosKafkaClockDrift}.
 *
 * <p>Shifts the realtime clock and adds RECV jitter to reproduce the compound failure profile of
 * Kafka timestamp routing under NTP desynchronisation, combined with a JVM TimestampException to
 * exercise application-level handling of timestamp-based routing errors.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class KafkaClockDriftComposer implements L3Composer<IncidentChaosKafkaClockDrift> {

  public KafkaClockDriftComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosKafkaClockDrift ann) {
    final List<Object> handles = new ArrayList<>();

    final var time = CompositeTimeChaos.standard().advanced();
    handles.add(
        time.apply(
            container,
            TimeRule.offset(
                TimeClock.REALTIME, Duration.ofMillis(ann.skewMs()), ann.probability())));

    final var conn = CompositeConnectionChaos.standard().advanced();
    handles.add(
        conn.apply(
            container,
            NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(20L), 1.0)));

    final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("KafkaClockDrift");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description("timestamp routing failure under clock skew")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "org.apache.kafka.common.errors.TimestampException",
                    "timestamp routing failure under clock skew"))
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
  public List<String> describe(final IncidentChaosKafkaClockDrift ann) {
    return List.of(
        "Kafka Clock Drift — timestamp routing failure under NTP desynchronisation",
        "time: REALTIME skew +" + ann.skewMs() + "ms, probability=" + ann.probability(),
        "connection: RECV latency 20ms (network jitter)",
        "jvm: TimestampException on class prefix '" + ann.classPattern() + "' (METHOD_ENTER)",
        "severity=MODERATE — messages in wrong partition segments, potential offset commit drift");
  }
}
