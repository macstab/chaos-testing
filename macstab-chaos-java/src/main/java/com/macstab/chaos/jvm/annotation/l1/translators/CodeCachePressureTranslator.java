/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;

/** Stressor L1 translator: code-cache pressure (force JIT to fill the code cache). */
public final class CodeCachePressureTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public CodeCachePressureTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    return JvmL1Translators.buildStressorScenarioAndPush(
        container,
        annotation,
        ChaosSelector.stress(ChaosSelector.StressTarget.CODE_CACHE_PRESSURE),
        ChaosEffect.codeCachePressure(
            JvmL1Translators.readInt(annotation, "classCount", 5_000),
            JvmL1Translators.readInt(annotation, "methodsPerClass", 50)));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
