/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.jdbc.testpack.l3.IncidentChaosJdbcNetworkPartition;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L3 composer for {@link IncidentChaosJdbcNetworkPartition}. */
@Slf4j
public final class JdbcNetworkPartitionComposer implements L3Composer<IncidentChaosJdbcNetworkPartition> {

    /** Public no-arg constructor required by the L3 composer contract. */
    public JdbcNetworkPartitionComposer() {}

    @Override
    public List<Object> apply(
            final GenericContainer<?> container, final IncidentChaosJdbcNetworkPartition annotation) {
        final List<Object> handles = new ArrayList<>();

        final RuleHandle timeoutHandle = CompositeConnectionChaos.standard().advanced()
                .apply(container, NetRule.timeout(
                        Endpoint.wildcard(),
                        Duration.ofMillis(annotation.timeoutMs()),
                        annotation.toxicity()));
        handles.add(timeoutHandle);

        final RuleHandle epipeHandle = CompositeConnectionChaos.standard().advanced()
                .apply(container, NetRule.errno(
                        Endpoint.wildcard(),
                        NetOperation.SEND,
                        Errno.EPIPE,
                        annotation.toxicity()));
        handles.add(epipeHandle);

        final String scenarioId = JvmPlanAccumulator.instance()
                .mintScenarioId(IncidentChaosJdbcNetworkPartition.class.getSimpleName());
        final NamePattern cls = annotation.classPattern().isBlank()
                ? NamePattern.any()
                : NamePattern.prefix(annotation.classPattern());
        final ChaosScenario scenario = ChaosScenario.builder(scenarioId)
                .description("L3 JDBC network partition — POLL timeout + EPIPE + partition exception")
                .selector(ChaosSelector.method(EnumSet.of(OperationType.METHOD_ENTER), cls, NamePattern.any()))
                .effect(ChaosEffect.injectException(
                        "java.sql.SQLException", "transaction aborted — network partition"))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        for (final Object h : handles) {
            if (h instanceof RuleHandle rh) {
                try {
                    new LibchaosTransport(LibchaosLib.NET).removeRules(container, rh.owner());
                } catch (final Exception e) {
                    log.warn("JdbcNetworkPartitionComposer.removeAll: failed to remove connection rule", e);
                }
            } else if (h instanceof String scenarioId) {
                try {
                    JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
                } catch (final Exception e) {
                    log.warn("JdbcNetworkPartitionComposer.removeAll: failed to remove JVM scenario {}", scenarioId, e);
                }
            }
        }
    }

    @Override
    public List<String> describe(final IncidentChaosJdbcNetworkPartition annotation) {
        return List.of(
                "L3 incident: JDBC network partition — 2PC in-doubt transactions under partition",
                "connection: timeout=" + annotation.timeoutMs() + "ms toxicity=" + annotation.toxicity() + " (POLL partition)",
                "connection: SEND EPIPE toxicity=" + annotation.toxicity() + " (severed connections)",
                "jvm: injectException(java.sql.SQLException) on class='" + annotation.classPattern() + "'",
                "severity=CRITICAL — in-doubt transactions block row locks; manual DBA intervention may be required");
    }
}
