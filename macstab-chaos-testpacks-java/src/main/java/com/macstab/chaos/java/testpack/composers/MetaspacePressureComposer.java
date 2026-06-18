/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosMetaspacePressure;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosMetaspacePressure}. */
@Slf4j
public final class MetaspacePressureComposer
    implements L2Composer<CompositeChaosMetaspacePressure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public MetaspacePressureComposer() {}

  // Approximate: each class with 10 fields is ~2 KB of metaspace; use 200 fields per class for MB
  // resolution.
  private static final int CLASSES_PER_MB = 10;
  private static final int FIELDS_PER_CLASS = 100;

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosMetaspacePressure annotation) {
    final int classCount = annotation.targetMb() * CLASSES_PER_MB;
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosMetaspacePressure.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: metaspace pressure — ~"
                    + annotation.targetMb()
                    + " MB, classCount="
                    + classCount
                    + ", fieldsPerClass="
                    + FIELDS_PER_CLASS)
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.METASPACE))
            .effect(ChaosEffect.metaspacePressure(classCount, FIELDS_PER_CLASS))
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
          log.warn("MetaspacePressureComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosMetaspacePressure annotation) {
    return List.of(
        "metaspace pressure — generating and loading synthetic classes to fill ~"
            + annotation.targetMb()
            + " MB of Metaspace",
        "severity=MODERATE — at exhaustion throws OutOfMemoryError: Metaspace");
  }
}
