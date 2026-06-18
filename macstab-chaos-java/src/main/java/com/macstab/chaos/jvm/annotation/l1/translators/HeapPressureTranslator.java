/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;

/**
 * Stressor L1 translator: heap pressure (retain N bytes in M-sized chunks).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class HeapPressureTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public HeapPressureTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final long bytes = JvmL1Translators.readLong(annotation, "bytes", 64L * 1024L * 1024L);
    final int chunkSize = JvmL1Translators.readInt(annotation, "chunkSizeBytes", 1024 * 1024);
    return JvmL1Translators.buildStressorScenarioAndPush(
        container,
        annotation,
        ChaosSelector.stress(ChaosSelector.StressTarget.HEAP),
        ChaosEffect.heapPressure(bytes, chunkSize));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
