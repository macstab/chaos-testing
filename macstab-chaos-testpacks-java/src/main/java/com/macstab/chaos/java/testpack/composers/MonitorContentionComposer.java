/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosMonitorContention;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosMonitorContention}. */
@Slf4j
public final class MonitorContentionComposer implements L2Composer<CompositeChaosMonitorContention> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public MonitorContentionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosMonitorContention annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosMonitorContention.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: monitor contention — " + annotation.threadCount()
                + " threads competing for synthetic lock, holdMs=" + annotation.lockHoldMs())
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.MONITOR_CONTENTION))
            .effect(ChaosEffect.monitorContention(
                java.time.Duration.ofMillis(annotation.lockHoldMs()),
                annotation.threadCount()))
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
          log.warn("MonitorContentionComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosMonitorContention annotation) {
    return List.of(
        "monitor contention — " + annotation.threadCount()
            + " threads competing for single synthetic lock, lockHoldMs=" + annotation.lockHoldMs(),
        "severity=MODERATE — elevated futex/context-switch rate; biased-lock revocation safepoints");
  }
}
