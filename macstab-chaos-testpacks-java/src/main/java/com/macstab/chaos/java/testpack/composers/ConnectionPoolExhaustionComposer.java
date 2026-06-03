/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosConnectionPoolExhaustion;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosConnectionPoolExhaustion}. */
@Slf4j
public final class ConnectionPoolExhaustionComposer
    implements L2Composer<CompositeChaosConnectionPoolExhaustion> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ConnectionPoolExhaustionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container,
      final CompositeChaosConnectionPoolExhaustion annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosConnectionPoolExhaustion.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.JDBC.build(EnumSet.of(OperationType.JDBC_CONNECTION_ACQUIRE));
    final Duration delay = Duration.ofMillis(annotation.acquireDelayMs());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: connection-pool exhaustion — JDBC acquire delay "
                + annotation.acquireDelayMs() + " ms")
            .selector(selector)
            .effect(ChaosEffect.delay(delay, delay))
            .activationPolicy(ActivationPolicy.always())
            .build();
    final String scenarioId = JvmPlanAccumulator.instance().addScenario(container, scenario);
    return List.of(scenarioId);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof String scenarioId) {
        try {
          JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
        } catch (final Exception e) {
          log.warn("ConnectionPoolExhaustionComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosConnectionPoolExhaustion annotation) {
    return List.of(
        "connection-pool exhaustion — every JDBC getConnection() delayed by "
            + annotation.acquireDelayMs() + " ms",
        "severity=SEVERE — pool timeouts will fire on every request; health checks fail");
  }
}
