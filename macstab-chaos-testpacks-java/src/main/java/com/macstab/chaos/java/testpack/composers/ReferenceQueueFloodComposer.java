/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosReferenceQueueFlood;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosReferenceQueueFlood}. */
@Slf4j
public final class ReferenceQueueFloodComposer
    implements L2Composer<CompositeChaosReferenceQueueFlood> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ReferenceQueueFloodComposer() {}

  private static final long FLOOD_INTERVAL_MS = 100L;

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosReferenceQueueFlood annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosReferenceQueueFlood.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: reference queue flood — " + annotation.objectCount()
                + " WeakReferences enqueued per " + FLOOD_INTERVAL_MS + " ms")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD))
            .effect(ChaosEffect.referenceQueueFlood(
                annotation.objectCount(), java.time.Duration.ofMillis(FLOOD_INTERVAL_MS)))
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
          log.warn("ReferenceQueueFloodComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosReferenceQueueFlood annotation) {
    return List.of(
        "reference queue flood — " + annotation.objectCount()
            + " WeakReference instances saturating the ReferenceHandler thread queue",
        "severity=MILD — delayed soft-reference clearing; transient memory pressure");
  }
}
