/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosClockSkewInProcess;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewMode;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosClockSkewInProcess}. */
@Slf4j
public final class ClockSkewInProcessComposer
    implements L2Composer<CompositeChaosClockSkewInProcess> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ClockSkewInProcessComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosClockSkewInProcess annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosClockSkewInProcess.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.JVM_RUNTIME.build(EnumSet.of(OperationType.SYSTEM_CLOCK_MILLIS));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: clock skew in-process — JVM clock offset by " + annotation.skewMs() + " ms")
            .selector(selector)
            .effect(
                ChaosEffect.skewClock(Duration.ofMillis(annotation.skewMs()), ClockSkewMode.FIXED))
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
          log.warn("ClockSkewInProcessComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosClockSkewInProcess annotation) {
    return List.of(
        "clock skew in-process — JVM Instant.now()/currentTimeMillis() skewed by "
            + annotation.skewMs()
            + " ms (FIXED mode)",
        "severity=MODERATE — JWT/OAuth token validation failures; distributed-lock TTL miscalculation");
  }
}
