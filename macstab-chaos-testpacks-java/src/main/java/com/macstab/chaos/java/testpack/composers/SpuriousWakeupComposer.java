/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosSpuriousWakeup;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosSpuriousWakeup}. */
@Slf4j
public final class SpuriousWakeupComposer implements L2Composer<CompositeChaosSpuriousWakeup> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public SpuriousWakeupComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosSpuriousWakeup annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosSpuriousWakeup.class.getSimpleName());
    // Uses NIO selector family — SELECTOR_SELECT is the closest available operation
    final ChaosSelector selector =
        JvmSelectorKind.NIO.build(EnumSet.of(OperationType.NIO_SELECTOR_SELECT));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: spurious wakeup — park/select returns spuriously with probability "
                    + annotation.probability())
            .selector(selector)
            .effect(ChaosEffect.spuriousWakeup())
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
          log.warn("SpuriousWakeupComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosSpuriousWakeup annotation) {
    return List.of(
        "spurious wakeup — NIO Selector.select() returns without events at probability "
            + annotation.probability(),
        "severity=MILD — exposes code that does not re-check wait condition in a loop");
  }
}
