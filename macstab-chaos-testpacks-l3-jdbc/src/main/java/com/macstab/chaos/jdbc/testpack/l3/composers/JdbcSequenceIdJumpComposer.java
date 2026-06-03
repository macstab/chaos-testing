/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3.composers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jdbc.testpack.l3.IncidentChaosJdbcSequenceIdJump;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import lombok.extern.slf4j.Slf4j;

/**
 * Composer for {@link IncidentChaosJdbcSequenceIdJump}.
 *
 * <p>Injects DataIntegrityViolationException at METHOD_EXIT on JDBC connection acquire paths
 * to reproduce the application-level symptom of Postgres sequence pre-allocation gaps after
 * primary failover reconnect.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class JdbcSequenceIdJumpComposer implements L3Composer<IncidentChaosJdbcSequenceIdJump> {

    /** Public no-arg constructor required by the L3 composer contract. */
    public JdbcSequenceIdJumpComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosJdbcSequenceIdJump ann) {
        final List<Object> handles = new ArrayList<>();

        final String scenarioId = JvmPlanAccumulator.instance()
                .mintScenarioId(IncidentChaosJdbcSequenceIdJump.class.getSimpleName());
        final NamePattern cls = ann.classPattern().isBlank()
                ? NamePattern.any()
                : NamePattern.prefix(ann.classPattern());
        final ChaosScenario scenario = ChaosScenario.builder(scenarioId)
                .description("L3 JDBC sequence ID jump — Postgres pre-allocation gap after failover reconnect")
                .selector(ChaosSelector.method(EnumSet.of(OperationType.METHOD_EXIT), cls, NamePattern.any()))
                .effect(ChaosEffect.injectException(
                        "org.springframework.dao.DataIntegrityViolationException",
                        "Postgres sequence pre-allocation gap after failover — ID jump detected"))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        for (final Object h : handles) {
            if (h instanceof String scenarioId) {
                try {
                    JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
                } catch (final Exception e) {
                    log.warn("JdbcSequenceIdJumpComposer.removeAll: failed to remove JVM scenario {}", scenarioId, e);
                }
            }
        }
    }

    @Override
    public List<String> describe(final IncidentChaosJdbcSequenceIdJump ann) {
        return List.of(
                "JDBC Sequence ID Jump — Postgres sequence pre-allocation gap after failover",
                "jvm: DataIntegrityViolationException on class prefix '" + ann.classPattern() + "' (METHOD_EXIT)",
                "severity=SEVERE — pagination breaks; unique constraint violations; dense-ID assumptions violated (incident.io 2025)");
    }
}
