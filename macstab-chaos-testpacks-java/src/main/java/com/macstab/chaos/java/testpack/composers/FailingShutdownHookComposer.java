/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosFailingShutdownHook;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosFailingShutdownHook}. */
@Slf4j
public final class FailingShutdownHookComposer implements L2Composer<CompositeChaosFailingShutdownHook> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public FailingShutdownHookComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosFailingShutdownHook annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosFailingShutdownHook.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.SHUTDOWN.build(EnumSet.of(OperationType.SHUTDOWN_HOOK_REGISTER));
    final Duration delay = Duration.ofMillis(annotation.hangMs());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: failing shutdown hook — shutdown-hook registration delayed by "
                + annotation.hangMs() + " ms, preventing clean JVM exit")
            .selector(selector)
            .effect(ChaosEffect.delay(delay, delay))
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
          log.warn("FailingShutdownHookComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosFailingShutdownHook annotation) {
    return List.of(
        "failing shutdown hook — Runtime.addShutdownHook() blocked for "
            + annotation.hangMs() + " ms, JVM shutdown subsystem seized",
        "severity=CRITICAL — exceeds Kubernetes SIGKILL grace period; connection pools, WAL, and locks not released");
  }
}
