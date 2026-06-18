/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;

/** Stressor L1 translator: finalizer backlog (slow finalizers back up the queue). */
public final class FinalizerBacklogTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public FinalizerBacklogTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    return JvmL1Translators.buildStressorScenarioAndPush(
        container,
        annotation,
        ChaosSelector.stress(ChaosSelector.StressTarget.FINALIZER_BACKLOG),
        ChaosEffect.finalizerBacklog(
            JvmL1Translators.readInt(annotation, "objectCount", 1000),
            java.time.Duration.ofMillis(
                JvmL1Translators.readLong(annotation, "finalizerDelayMs", 100L))));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
