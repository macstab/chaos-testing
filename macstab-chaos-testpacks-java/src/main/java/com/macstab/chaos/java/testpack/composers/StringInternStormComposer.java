/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosStringInternStorm;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosStringInternStorm}. */
@Slf4j
public final class StringInternStormComposer implements L2Composer<CompositeChaosStringInternStorm> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public StringInternStormComposer() {}

  private static final int STRING_LENGTH_BYTES = 64;

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosStringInternStorm annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosStringInternStorm.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: string intern storm — " + annotation.stringCount() + " unique strings interned")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.STRING_INTERN_PRESSURE))
            .effect(ChaosEffect.stringInternPressure(annotation.stringCount(), STRING_LENGTH_BYTES))
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
          log.warn("StringInternStormComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosStringInternStorm annotation) {
    return List.of(
        "string intern storm — " + annotation.stringCount()
            + " unique strings permanently pinned in JVM string pool",
        "severity=MODERATE — O(n) GC weak-reference scanning cost; extends every GC pause");
  }
}
