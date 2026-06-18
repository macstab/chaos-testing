/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;

/**
 * Stressor L1 translator: ThreadLocal leak (large request-scoped objects retained per pool thread).
 */
public final class ThreadLocalLeakTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ThreadLocalLeakTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    return JvmL1Translators.buildStressorScenarioAndPush(
        container,
        annotation,
        ChaosSelector.stress(ChaosSelector.StressTarget.THREAD_LOCAL_LEAK),
        ChaosEffect.threadLocalLeak(
            JvmL1Translators.readInt(annotation, "entriesPerThread", 100),
            JvmL1Translators.readInt(annotation, "valueSizeBytes", 64 * 1024)));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
