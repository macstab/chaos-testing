/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosMethodReturnCorruption;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEffect.ReturnValueStrategy;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosMethodReturnCorruption}. */
@Slf4j
public final class MethodReturnCorruptionComposer
    implements L2Composer<CompositeChaosMethodReturnCorruption> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public MethodReturnCorruptionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosMethodReturnCorruption annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosMethodReturnCorruption.class.getSimpleName());
    final NamePattern cls =
        annotation.classPattern().isBlank()
            ? NamePattern.any()
            : NamePattern.prefix(annotation.classPattern());
    final NamePattern mth =
        annotation.methodNamePattern().isBlank()
            ? NamePattern.any()
            : NamePattern.prefix(annotation.methodNamePattern());
    final ChaosSelector selector =
        ChaosSelector.method(EnumSet.of(OperationType.METHOD_EXIT), cls, mth);
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: method return corruption — null/zero substituted at METHOD_EXIT for class='"
                + annotation.classPattern() + "' method='" + annotation.methodNamePattern() + "'")
            .selector(selector)
            .effect(ChaosEffect.corruptReturnValue(ReturnValueStrategy.NULL))
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
          log.warn("MethodReturnCorruptionComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosMethodReturnCorruption annotation) {
    return List.of(
        "method return corruption — return value replaced with null/zero at METHOD_EXIT for class='"
            + annotation.classPattern() + "' method='" + annotation.methodNamePattern() + "'",
        "severity=SEVERE — silent data corruption; method body executes but caller receives wrong result");
  }
}
