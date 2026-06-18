/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosShutdownHookHang;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosShutdownHookHang}. */
@Slf4j
public final class ShutdownHookHangComposer implements L2Composer<CompositeChaosShutdownHookHang> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ShutdownHookHangComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosShutdownHookHang annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosShutdownHookHang.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.SHUTDOWN.build(EnumSet.of(OperationType.SHUTDOWN_HOOK_REGISTER));
    final Duration delay = Duration.ofMillis(annotation.hangMs());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: shutdown-hook hang — registration delayed by " + annotation.hangMs() + " ms")
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
          log.warn("ShutdownHookHangComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosShutdownHookHang annotation) {
    return List.of(
        "shutdown-hook hang — Runtime.addShutdownHook() blocked for " + annotation.hangMs() + " ms",
        "severity=SEVERE — exceeds Kubernetes termination grace period; SIGKILL skips remaining hooks");
  }
}
