/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.spring.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.spring.testpack.l3.IncidentChaosSpringWebFluxReactorStarvation;
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
 * Composer for {@link IncidentChaosSpringWebFluxReactorStarvation}.
 *
 * <p>Applies RECV latency on all connections and a JVM Reactor exception to reproduce the
 * compound failure profile of a blocking call on a reactor carrier thread — health endpoint
 * times out, pod is killed. (JDriven post-mortem)
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpringWebFluxReactorStarvationComposer implements L3Composer<IncidentChaosSpringWebFluxReactorStarvation> {

    public SpringWebFluxReactorStarvationComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosSpringWebFluxReactorStarvation ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

        final String id = JvmPlanAccumulator.instance().mintScenarioId("SpringWebFluxReactorStarvation-exc");
        final var sc = ChaosScenario.builder(id)
                .description("Spring WebFlux reactor starvation — blocking on reactor thread monopolizes all carriers")
                .selector(ChaosSelector.method(
                        EnumSet.of(OperationType.METHOD_EXIT),
                        NamePattern.prefix(ann.classPattern()),
                        NamePattern.any()))
                .effect(ChaosEffect.injectException(
                        "reactor.core.publisher.Operators$OnNextFailedException",
                        "Operator called default onErrorDropped"))
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
    public List<String> describe(final IncidentChaosSpringWebFluxReactorStarvation ann) {
        return List.of(
                "Spring WebFlux Reactor Starvation — blocking on reactor thread monopolizes all carriers",
                "connection: RECV latency=" + ann.latencyMs() + "ms (downstream I/O blocks reactor threads)",
                "jvm: Reactor exception injection on '" + ann.classPattern() + "'",
                "severity=CRITICAL — health endpoint times out → pod killed; no queue overflow visible (JDriven post-mortem)");
    }
}
