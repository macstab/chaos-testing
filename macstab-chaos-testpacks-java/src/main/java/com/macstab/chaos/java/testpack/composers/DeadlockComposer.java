/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosDeadlock;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosDeadlock}. */
@Slf4j
public final class DeadlockComposer implements L2Composer<CompositeChaosDeadlock> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public DeadlockComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosDeadlock annotation) {
    final String id =
        JvmPlanAccumulator.instance().mintScenarioId(CompositeChaosDeadlock.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: deadlock stressor — " + annotation.threadCount()
                + " threads in circular lock-acquisition ring")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DEADLOCK))
            .effect(ChaosEffect.deadlock(annotation.threadCount()))
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
          log.warn("DeadlockComposer.removeAll: failed to remove scenario {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosDeadlock annotation) {
    return List.of(
        "deadlock stressor — " + annotation.threadCount()
            + " synthetic threads in permanent circular-wait lock ring",
        "detectable via ThreadMXBean.findDeadlockedThreads()",
        "severity=CRITICAL — zero forward progress on deadlocked code paths; restart required");
  }
}
