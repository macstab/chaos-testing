/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosCodeCachePressure;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosCodeCachePressure}. */
@Slf4j
public final class CodeCachePressureComposer
    implements L2Composer<CompositeChaosCodeCachePressure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public CodeCachePressureComposer() {}

  // Approximation: 1 MB ≈ 200 classes × 50 methods, each ~100 bytes of native code
  private static final int CLASSES_PER_MB = 200;
  private static final int METHODS_PER_CLASS = 50;

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosCodeCachePressure annotation) {
    final int classCount = annotation.targetMb() * CLASSES_PER_MB;
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosCodeCachePressure.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: code-cache pressure — ~"
                    + annotation.targetMb()
                    + " MB, classCount="
                    + classCount
                    + ", methodsPerClass="
                    + METHODS_PER_CLASS)
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.CODE_CACHE_PRESSURE))
            .effect(ChaosEffect.codeCachePressure(classCount, METHODS_PER_CLASS))
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
          log.warn("CodeCachePressureComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosCodeCachePressure annotation) {
    return List.of(
        "code-cache pressure — filling JIT code cache with ~"
            + annotation.targetMb()
            + " MB of synthetic compiled methods",
        "severity=MODERATE — JIT disabled when cache is full; interpreted-mode performance degradation");
  }
}
