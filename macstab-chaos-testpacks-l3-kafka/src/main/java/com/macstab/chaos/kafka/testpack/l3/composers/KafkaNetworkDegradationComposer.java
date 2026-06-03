/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kafka.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.kafka.testpack.l3.IncidentChaosKafkaNetworkDegradation;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Composer for {@link IncidentChaosKafkaNetworkDegradation}.
 *
 * <p>Injects bidirectional latency on SEND and RECV to simulate sustained network degradation
 * between Kafka clients and the cluster, combined with a JVM NetworkException to exercise
 * application-level error handling for timeout-induced network failures.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class KafkaNetworkDegradationComposer implements L3Composer<IncidentChaosKafkaNetworkDegradation> {

    public KafkaNetworkDegradationComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosKafkaNetworkDegradation ann) {
        final List<Object> handles = new ArrayList<>();

        final var conn = CompositeConnectionChaos.standard().advanced();
        handles.add(conn.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.SEND, Duration.ofMillis(ann.latencyMs()), 1.0)));
        handles.add(conn.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

        final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("KafkaNetworkDegradation");
        final var selector = ChaosSelector.method(
                EnumSet.of(OperationType.METHOD_ENTER),
                NamePattern.prefix(ann.classPattern()),
                NamePattern.any());
        final var scenario = ChaosScenario.builder(scenarioId)
                .description("sustained throughput drop — consumer lag building")
                .selector(selector)
                .effect(ChaosEffect.injectException(
                        "org.apache.kafka.common.errors.NetworkException",
                        "sustained throughput drop — consumer lag building"))
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
    public List<String> describe(final IncidentChaosKafkaNetworkDegradation ann) {
        return List.of(
                "Kafka Network Degradation — sustained bidirectional latency, consumer lag buildup",
                "connection: SEND latency " + ann.latencyMs() + "ms",
                "connection: RECV latency " + ann.latencyMs() + "ms (bidirectional)",
                "jvm: NetworkException on class prefix '" + ann.classPattern() + "' (METHOD_ENTER)",
                "severity=MODERATE — consumer lag accumulates, eventual rebalance under sustained degradation");
    }
}
