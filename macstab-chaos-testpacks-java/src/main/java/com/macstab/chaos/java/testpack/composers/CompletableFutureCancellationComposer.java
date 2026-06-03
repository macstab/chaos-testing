/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosCompletableFutureCancellation;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosCompletableFutureCancellation}. */
@Slf4j
public final class CompletableFutureCancellationComposer
    implements L2Composer<CompositeChaosCompletableFutureCancellation> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public CompletableFutureCancellationComposer() {}

  // Fixed 2-second delay to simulate slow cancellation propagation
  private static final Duration CANCELLATION_DELAY = Duration.ofSeconds(2);

  @Override
  public List<Object> apply(
      final GenericContainer<?> container,
      final CompositeChaosCompletableFutureCancellation annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosCompletableFutureCancellation.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.ASYNC.build(EnumSet.of(OperationType.ASYNC_CANCEL));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: CompletableFuture cancellation — ASYNC_CANCEL delayed by "
                + CANCELLATION_DELAY.toMillis() + " ms at probability " + annotation.probability())
            .selector(selector)
            .effect(ChaosEffect.delay(CANCELLATION_DELAY, CANCELLATION_DELAY))
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
          log.warn("CompletableFutureCancellationComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosCompletableFutureCancellation annotation) {
    return List.of(
        "CompletableFuture cancellation — CompletableFuture.cancel() delayed by "
            + CANCELLATION_DELAY.toMillis() + " ms at probability " + annotation.probability(),
        "severity=MODERATE — phantom work continues after cancellation; resource exhaustion in high-throughput systems");
  }
}
