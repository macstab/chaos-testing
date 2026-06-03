/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosThreadStarvation;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosThreadStarvation}. */
@Slf4j
public final class ThreadStarvationComposer implements L2Composer<CompositeChaosThreadStarvation> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ThreadStarvationComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosThreadStarvation annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosThreadStarvation.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: thread-leak stressor — " + annotation.exhaustAfter()
                + " never-terminating daemon threads")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.THREAD_LEAK))
            .effect(ChaosEffect.threadLeak(annotation.exhaustAfter(), "chaos-l2-leaked-", true))
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
          log.warn("ThreadStarvationComposer.removeAll: failed to remove scenario {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosThreadStarvation annotation) {
    return List.of(
        "thread-leak stressor — " + annotation.exhaustAfter()
            + " never-terminating daemon threads consuming OS thread slots",
        "severity=SEVERE — thread-pool exhaustion leads to OutOfMemoryError: unable to create native thread");
  }
}
