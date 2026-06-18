/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosSafepointStorm;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosSafepointStorm}. */
@Slf4j
public final class SafepointStormComposer implements L2Composer<CompositeChaosSafepointStorm> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public SafepointStormComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosSafepointStorm annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosSafepointStorm.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: safepoint storm — forced STW safepoints every "
                    + annotation.intervalMs()
                    + " ms")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.SAFEPOINT_STORM))
            .effect(
                ChaosEffect.safepointStorm(java.time.Duration.ofMillis(annotation.intervalMs())))
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
          log.warn("SafepointStormComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosSafepointStorm annotation) {
    return List.of(
        "safepoint storm — forced stop-the-world safepoints every "
            + annotation.intervalMs()
            + " ms",
        "severity=SEVERE — all application threads pause simultaneously; throughput collapses");
  }
}
