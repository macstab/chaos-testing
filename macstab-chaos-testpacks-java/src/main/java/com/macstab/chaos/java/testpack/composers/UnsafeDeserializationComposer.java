/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosUnsafeDeserialization;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosUnsafeDeserialization}. */
@Slf4j
public final class UnsafeDeserializationComposer
    implements L2Composer<CompositeChaosUnsafeDeserialization> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public UnsafeDeserializationComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosUnsafeDeserialization annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosUnsafeDeserialization.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.JVM_RUNTIME.build(EnumSet.of(OperationType.OBJECT_DESERIALIZE));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: unsafe deserialization — InvalidClassException injected at probability "
                    + annotation.probability())
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.io.InvalidClassException",
                    "injected deserialization failure by chaos L2"))
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
          log.warn("UnsafeDeserializationComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosUnsafeDeserialization annotation) {
    return List.of(
        "unsafe deserialization — InvalidClassException injected into ObjectInputStream.readObject() at probability "
            + annotation.probability(),
        "severity=CRITICAL — validates handling of corrupt/tampered serialised payloads without exposing internal state");
  }
}
