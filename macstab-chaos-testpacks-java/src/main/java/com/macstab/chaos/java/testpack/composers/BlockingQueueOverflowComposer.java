/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosBlockingQueueOverflow;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosBlockingQueueOverflow}. */
@Slf4j
public final class BlockingQueueOverflowComposer
    implements L2Composer<CompositeChaosBlockingQueueOverflow> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public BlockingQueueOverflowComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosBlockingQueueOverflow annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosBlockingQueueOverflow.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.QUEUE.build(EnumSet.of(OperationType.QUEUE_OFFER));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: blocking queue overflow — QUEUE_OFFER suppressed at probability "
                + annotation.probability())
            .selector(selector)
            .effect(ChaosEffect.suppress())
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
          log.warn("BlockingQueueOverflowComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosBlockingQueueOverflow annotation) {
    return List.of(
        "blocking queue overflow — BlockingQueue.offer() suppressed (returns false) at probability "
            + annotation.probability(),
        "severity=MODERATE — producers silently lose events or block; validates back-pressure handling");
  }
}
