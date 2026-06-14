/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosVirtualThreadPinning;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosVirtualThreadPinning}. */
@Slf4j
public final class VirtualThreadPinningComposer
    implements L2Composer<CompositeChaosVirtualThreadPinning> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public VirtualThreadPinningComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosVirtualThreadPinning annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosVirtualThreadPinning.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: virtual-thread carrier pinning — "
                    + annotation.pinnedThreadCount()
                    + " carriers pinned for "
                    + annotation.durationMs()
                    + " ms each")
            .selector(
                ChaosSelector.stress(ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
            .effect(
                ChaosEffect.virtualThreadCarrierPinning(
                    annotation.pinnedThreadCount(),
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
          log.warn("VirtualThreadPinningComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosVirtualThreadPinning annotation) {
    return List.of(
        "virtual-thread carrier pinning — "
            + annotation.pinnedThreadCount()
            + " carrier threads held in synchronized blocks for "
            + annotation.durationMs()
            + " ms",
        "severity=MODERATE — effective virtual-thread parallelism reduced; JFR VirtualThreadPinned events fire");
  }
}
