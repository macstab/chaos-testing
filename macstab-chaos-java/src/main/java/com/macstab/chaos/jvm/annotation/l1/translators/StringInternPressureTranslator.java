/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;

/** Stressor L1 translator: string intern pressure (exhaust the JVM string table). */
public final class StringInternPressureTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public StringInternPressureTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    return JvmL1Translators.buildStressorScenarioAndPush(
        container,
        annotation,
        ChaosSelector.stress(ChaosSelector.StressTarget.STRING_INTERN_PRESSURE),
        ChaosEffect.stringInternPressure(
            JvmL1Translators.readInt(annotation, "internCount", 100_000),
            JvmL1Translators.readInt(annotation, "stringLengthBytes", 64)));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
