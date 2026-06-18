/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosFinalizerBacklog;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosFinalizerBacklog}. */
@Slf4j
public final class FinalizerBacklogComposer implements L2Composer<CompositeChaosFinalizerBacklog> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public FinalizerBacklogComposer() {}

  private static final long FINALIZER_DELAY_MS = 10L;

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosFinalizerBacklog annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosFinalizerBacklog.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: finalizer backlog — "
                    + annotation.objectCount()
                    + " objects with slow finalisers enqueued")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.FINALIZER_BACKLOG))
            .effect(
                ChaosEffect.finalizerBacklog(
                    annotation.objectCount(), java.time.Duration.ofMillis(FINALIZER_DELAY_MS)))
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
          log.warn("FinalizerBacklogComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosFinalizerBacklog annotation) {
    return List.of(
        "finalizer backlog — "
            + annotation.objectCount()
            + " finalizable objects with slow finalize() methods backing up the Finalizer thread queue",
        "severity=MODERATE — delayed resource reclamation; FD/native-memory leaks until queue drains");
  }
}
