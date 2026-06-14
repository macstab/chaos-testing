/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.cache.testpack.l3.IncidentChaosCaffeineEvictionDeadlock;
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
 * Composer for {@link IncidentChaosCaffeineEvictionDeadlock}.
 *
 * <p>Slows one cache loader via RECV latency so it holds the Caffeine {@code evictionLock}, then
 * injects a {@code TimeoutException} from the same loader to reproduce the thread pile-up on
 * unrelated cache keys described in Caffeine issue #672.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class CaffeineEvictionDeadlockComposer
    implements L3Composer<IncidentChaosCaffeineEvictionDeadlock> {

  public CaffeineEvictionDeadlockComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosCaffeineEvictionDeadlock ann) {
    final List<Object> handles = new ArrayList<>();

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.latency(
                Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

    final String id = JvmPlanAccumulator.instance().mintScenarioId("CaffeineEvictionDeadlock");
    final ChaosScenario sc =
        ChaosScenario.builder(id)
            .description("Caffeine Eviction Deadlock — slow loader holds evictionLock")
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_EXIT),
                    NamePattern.prefix(ann.classPattern()),
                    NamePattern.any()))
            .effect(
                ChaosEffect.injectException(
                    "java.util.concurrent.TimeoutException", "Caffeine loader timed out"))
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
  public List<String> describe(final IncidentChaosCaffeineEvictionDeadlock ann) {
    return List.of(
        "Caffeine Eviction Deadlock — slow loader holds evictionLock → 1400 threads blocked on unrelated keys",
        "connection: RECV latency=" + ann.latencyMs() + "ms (loader timeout trigger)",
        "jvm: TimeoutException injection on '" + ann.classPattern() + "'",
        "severity=SEVERE — cache throughput zero; restart required (Caffeine #672)");
  }
}
