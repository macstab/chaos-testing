/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.cache.testpack.l3.IncidentChaosCacheStampede;
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

/**
 * Composer for {@link IncidentChaosCacheStampede}.
 *
 * <p>Applies RECV latency on the backing-store connection and forces null returns from the cache
 * abstraction layer to reproduce the thundering-herd death spiral of a cache stampede.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class CacheStampedeComposer implements L3Composer<IncidentChaosCacheStampede> {

  public CacheStampedeComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosCacheStampede ann) {
    final List<Object> handles = new ArrayList<>();

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.latency(
                Endpoint.wildcard(),
                NetOperation.RECV,
                Duration.ofMillis(ann.latencyMs()),
                ann.toxicity())));

    final String id = JvmPlanAccumulator.instance().mintScenarioId("CacheStampede-exc");
    final ChaosScenario sc =
        ChaosScenario.builder(id)
            .description("Cache Stampede — force null return on cache lookups")
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_EXIT),
                    NamePattern.prefix(ann.classPattern()),
                    NamePattern.any()))
            .effect(ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL))
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
  public List<String> describe(final IncidentChaosCacheStampede ann) {
    return List.of(
        "Cache Stampede — mass cache-miss thundering-herd against backing store",
        "connection: RECV latency="
            + ann.latencyMs()
            + "ms toxicity="
            + ann.toxicity()
            + " (DB overload)",
        "jvm: corruptReturnValue(NULL) on '" + ann.classPattern() + "' (METHOD_EXIT)",
        "severity=CRITICAL — Twitter/Reddit/Instagram pattern; DB locks → cache refill fails → death spiral");
  }
}
