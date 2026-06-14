/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosZipBomb;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

import lombok.extern.slf4j.Slf4j;

/**
 * L2 composer for {@link CompositeChaosZipBomb}.
 *
 * <p>Simulates zip-bomb decompression pressure using GC heap pressure at an extreme allocation
 * rate, since the JVM chaos agent does not expose a dedicated Inflater interception point. The
 * effect mirrors the heap exhaustion pattern that a real zip bomb produces.
 */
@Slf4j
public final class ZipBombComposer implements L2Composer<CompositeChaosZipBomb> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ZipBombComposer() {}

  // 1 GB/s allocation rate simulates the rapid heap fill of decompressing a zip bomb
  private static final long ALLOCATION_RATE = 1_024L * 1_024L * 1_024L;
  private static final long DURATION_MS = 5_000L;

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosZipBomb annotation) {
    final String id =
        JvmPlanAccumulator.instance().mintScenarioId(CompositeChaosZipBomb.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: zip-bomb pressure — extreme GC allocation rate simulating decompression explosion, probability="
                    + annotation.probability())
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.GC_PRESSURE))
            .effect(
                ChaosEffect.gcPressure(ALLOCATION_RATE, java.time.Duration.ofMillis(DURATION_MS)))
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
          log.warn("ZipBombComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosZipBomb annotation) {
    return List.of(
        "zip-bomb pressure — extreme allocation rate ("
            + ALLOCATION_RATE / (1024 * 1024)
            + " MB/s) simulating decompression explosion",
        "probability=" + annotation.probability(),
        "severity=SEVERE — heap exhaustion and OOM if decompression is not bounded");
  }
}
