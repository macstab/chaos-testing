/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;

/** Stressor L1 translator: pin virtual-thread carriers (synchronised block keeps them mounted). */
public final class VirtualThreadCarrierPinningTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public VirtualThreadCarrierPinningTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    return JvmL1Translators.buildStressorScenarioAndPush(
        container,
        annotation,
        ChaosSelector.stress(ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING),
        ChaosEffect.virtualThreadCarrierPinning(JvmL1Translators.readInt(annotation, "pinnedThreadCount", 4), java.time.Duration.ofMillis(JvmL1Translators.readLong(annotation, "pinDurationMs", 100L))));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
