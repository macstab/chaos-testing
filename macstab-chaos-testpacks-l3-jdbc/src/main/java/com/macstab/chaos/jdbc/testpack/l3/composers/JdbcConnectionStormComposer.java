/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3.composers;

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
import com.macstab.chaos.jdbc.testpack.l3.IncidentChaosJdbcConnectionStorm;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L3 composer for {@link IncidentChaosJdbcConnectionStorm}. */
@Slf4j
public final class JdbcConnectionStormComposer implements L3Composer<IncidentChaosJdbcConnectionStorm> {

    /** Public no-arg constructor required by the L3 composer contract. */
    public JdbcConnectionStormComposer() {}

    @Override
    public List<Object> apply(
            final GenericContainer<?> container, final IncidentChaosJdbcConnectionStorm annotation) {
        final List<Object> handles = new ArrayList<>();

        final RuleHandle connHandle = CompositeConnectionChaos.standard().advanced()
                .apply(container, NetRule.errno(
                        Endpoint.wildcard(),
                        NetOperation.CONNECT,
                        Errno.ECONNREFUSED,
                        annotation.toxicity()));
        handles.add(connHandle);

        final String scenarioId = JvmPlanAccumulator.instance()
                .mintScenarioId(IncidentChaosJdbcConnectionStorm.class.getSimpleName());
        final NamePattern cls = annotation.classPattern().isBlank()
                ? NamePattern.any()
                : NamePattern.prefix(annotation.classPattern());
        final ChaosScenario scenario = ChaosScenario.builder(scenarioId)
                .description("L3 JDBC connection storm — ECONNREFUSED + pool exhaustion exception")
                .selector(ChaosSelector.method(EnumSet.of(OperationType.METHOD_ENTER), cls, NamePattern.any()))
                .effect(ChaosEffect.injectException("java.sql.SQLException", "connection pool exhausted"))
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
                    log.warn("JdbcConnectionStormComposer.removeAll: failed to remove connection rule", e);
                }
            } else if (h instanceof String scenarioId) {
                try {
                    JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
                } catch (final Exception e) {
                    log.warn("JdbcConnectionStormComposer.removeAll: failed to remove JVM scenario {}", scenarioId, e);
                }
            }
        }
    }

    @Override
    public List<String> describe(final IncidentChaosJdbcConnectionStorm annotation) {
        return List.of(
                "L3 incident: JDBC connection storm — pool exhaustion under load spike",
                "connection: CONNECT ECONNREFUSED toxicity=" + annotation.toxicity(),
                "jvm: injectException(java.sql.SQLException) on class='" + annotation.classPattern() + "'",
                "severity=CRITICAL — service degrades to complete unavailability within seconds");
    }
}
