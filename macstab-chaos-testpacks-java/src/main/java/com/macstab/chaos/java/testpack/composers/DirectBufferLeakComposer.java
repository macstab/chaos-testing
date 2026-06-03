/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosDirectBufferLeak;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosDirectBufferLeak}. */
@Slf4j
public final class DirectBufferLeakComposer implements L2Composer<CompositeChaosDirectBufferLeak> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public DirectBufferLeakComposer() {}

  private static final int BUFFER_SIZE_BYTES = 1024 * 1024; // 1 MB chunks

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosDirectBufferLeak annotation) {
    final long totalBytes = (long) annotation.targetMb() * 1024L * 1024L;
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosDirectBufferLeak.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: direct-buffer leak — " + annotation.targetMb() + " MB off-heap retained")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
            .effect(ChaosEffect.directBufferPressure(totalBytes, BUFFER_SIZE_BYTES))
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
          log.warn("DirectBufferLeakComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosDirectBufferLeak annotation) {
    return List.of(
        "direct-buffer leak — " + annotation.targetMb() + " MB of NIO ByteBuffer.allocateDirect() retained",
        "severity=SEVERE — subsequent allocateDirect() throws OutOfMemoryError: Direct buffer memory");
  }
}
