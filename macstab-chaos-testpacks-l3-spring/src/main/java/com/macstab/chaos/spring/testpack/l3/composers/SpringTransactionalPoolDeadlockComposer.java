/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.spring.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.spring.testpack.l3.IncidentChaosSpringTransactionalPoolDeadlock;
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
 * Composer for {@link IncidentChaosSpringTransactionalPoolDeadlock}.
 *
 * <p>Applies RECV latency on all connections and a JVM DataAccessResourceFailureException to
 * reproduce the compound failure profile of {@code @Transactional(REQUIRES_NEW)} pool
 * exhaustion — all threads freeze, no DB deadlock is visible. (Spring #26250)
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpringTransactionalPoolDeadlockComposer implements L3Composer<IncidentChaosSpringTransactionalPoolDeadlock> {

    public SpringTransactionalPoolDeadlockComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosSpringTransactionalPoolDeadlock ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

        final String id = JvmPlanAccumulator.instance().mintScenarioId("SpringTransactionalPoolDeadlock-exc");
        final var sc = ChaosScenario.builder(id)
                .description("Spring @Transactional pool deadlock — REQUIRES_NEW exhausts connection pool")
                .selector(ChaosSelector.method(
                        EnumSet.of(OperationType.METHOD_EXIT),
                        NamePattern.prefix(ann.classPattern()),
                        NamePattern.any()))
                .effect(ChaosEffect.injectException(
                        "org.springframework.dao.DataAccessResourceFailureException",
                        "Unable to acquire JDBC Connection"))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, sc));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosSpringTransactionalPoolDeadlock ann) {
        return List.of(
                "Spring @Transactional Pool Deadlock — REQUIRES_NEW nested transaction exhausts connection pool",
                "connection: RECV latency=" + ann.latencyMs() + "ms (DB connection saturation)",
                "jvm: DataAccessResourceFailureException injection on '" + ann.classPattern() + "'",
                "severity=CRITICAL — all threads freeze; no DB deadlock log; requires pool exhaustion detection (Spring #26250)");
    }
}
