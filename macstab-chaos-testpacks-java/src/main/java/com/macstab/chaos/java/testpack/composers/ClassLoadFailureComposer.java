/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosClassLoadFailure;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosClassLoadFailure}. */
@Slf4j
public final class ClassLoadFailureComposer implements L2Composer<CompositeChaosClassLoadFailure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ClassLoadFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosClassLoadFailure annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosClassLoadFailure.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.CLASS_LOADING.build(EnumSet.of(OperationType.CLASS_LOAD));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: class load failure — ClassNotFoundException injected for pattern '"
                    + annotation.classPattern()
                    + "'")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.lang.ClassNotFoundException",
                    "injected class load failure by chaos L2 for pattern: "
                        + annotation.classPattern()))
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
          log.warn("ClassLoadFailureComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosClassLoadFailure annotation) {
    return List.of(
        "class load failure — ClassNotFoundException injected for class pattern '"
            + annotation.classPattern()
            + "'",
        "severity=SEVERE — core class load failure prevents context startup; optional class load must be handled gracefully");
  }
}
