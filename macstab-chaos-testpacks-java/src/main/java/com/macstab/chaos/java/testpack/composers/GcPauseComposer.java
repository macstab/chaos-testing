/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosGcPause;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosGcPause}. */
@Slf4j
public final class GcPauseComposer implements L2Composer<CompositeChaosGcPause> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public GcPauseComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosGcPause annotation) {
    final String id =
        JvmPlanAccumulator.instance().mintScenarioId(CompositeChaosGcPause.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: GC pressure stressor — allocationRate="
                + annotation.allocationRateBytesPerSecond() + " B/s, durationMs="
                + annotation.durationMs())
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.GC_PRESSURE))
            .effect(ChaosEffect.gcPressure(
                annotation.allocationRateBytesPerSecond(),
                java.time.Duration.ofMillis(annotation.durationMs())))
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
          log.warn("GcPauseComposer.removeAll: failed to remove scenario {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosGcPause annotation) {
    return List.of(
        "GC pressure stressor — sustained allocation rate drives frequent stop-the-world minor GCs",
        "allocationRateBytesPerSecond=" + annotation.allocationRateBytesPerSecond(),
        "durationMs=" + annotation.durationMs(),
        "severity=MODERATE — P99/P999 latency spikes; service stays responsive");
  }
}
