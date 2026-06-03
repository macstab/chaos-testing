/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosScheduledTaskMissed;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosScheduledTaskMissed}. */
@Slf4j
public final class ScheduledTaskMissedComposer
    implements L2Composer<CompositeChaosScheduledTaskMissed> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ScheduledTaskMissedComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosScheduledTaskMissed annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosScheduledTaskMissed.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.SCHEDULING.build(EnumSet.of(OperationType.SCHEDULE_TICK));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: scheduled task missed — SCHEDULE_TICK suppressed at probability "
                + annotation.probability())
            .selector(selector)
            .effect(ChaosEffect.suppress())
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
          log.warn("ScheduledTaskMissedComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosScheduledTaskMissed annotation) {
    return List.of(
        "scheduled task missed — ScheduledExecutorService ticks suppressed at probability "
            + annotation.probability(),
        "severity=MODERATE — heartbeat/lease-renewal tasks fail silently; downstream observes the miss");
  }
}
