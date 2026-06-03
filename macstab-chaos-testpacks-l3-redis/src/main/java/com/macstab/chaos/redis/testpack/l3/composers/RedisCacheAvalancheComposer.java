/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.redis.testpack.l3.IncidentChaosRedisCacheAvalanche;
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
 * Composer for {@link IncidentChaosRedisCacheAvalanche}.
 *
 * <p>Slows new Redis connections and forces cache lookup methods to return null to reproduce
 * the mass cache-miss thundering-herd against the backing store.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisCacheAvalancheComposer implements L3Composer<IncidentChaosRedisCacheAvalanche> {

    public RedisCacheAvalancheComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosRedisCacheAvalanche ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.CONNECT, Duration.ofMillis(ann.latencyMs()), ann.toxicity())));

        final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("RedisCacheAvalanche");
        final var selector = ChaosSelector.method(
                EnumSet.of(OperationType.METHOD_EXIT),
                NamePattern.prefix(ann.classPattern()),
                NamePattern.any());
        final var scenario = ChaosScenario.builder(scenarioId)
                .description("Redis cache avalanche — force null return on cache lookups")
                .selector(selector)
                .effect(ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL))
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
    public List<String> describe(final IncidentChaosRedisCacheAvalanche ann) {
        return List.of(
                "Redis Cache Avalanche — mass key expiry with null-return injection",
                "connection: CONNECT latency " + ann.latencyMs() + "ms, toxicity=" + ann.toxicity(),
                "jvm: replaceReturn() on class prefix '" + ann.classPattern() + "' (METHOD_EXIT)",
                "severity=CRITICAL — backing store absorbs full request load unshielded");
    }
}
