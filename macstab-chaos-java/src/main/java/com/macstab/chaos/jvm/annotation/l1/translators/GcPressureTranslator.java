/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;

/** Stressor L1 translator: GC pressure (sustained allocation rate). */
public final class GcPressureTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public GcPressureTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    return JvmL1Translators.buildStressorScenarioAndPush(
        container,
        annotation,
        ChaosSelector.stress(ChaosSelector.StressTarget.GC_PRESSURE),
        ChaosEffect.gcPressure(
            JvmL1Translators.readLong(
                annotation, "allocationRateBytesPerSecond", 100L * 1024L * 1024L),
            java.time.Duration.ofMillis(
                JvmL1Translators.readLong(annotation, "durationMs", 60_000L))));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
