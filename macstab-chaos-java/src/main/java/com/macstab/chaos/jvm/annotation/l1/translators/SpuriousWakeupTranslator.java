/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;

/**
 * L1 translator for {@code @ChaosNioSelectSpuriousWakeup}. Builds the no-arg
 * {@link ChaosEffect#spuriousWakeup()}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpuriousWakeupTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public SpuriousWakeupTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    return JvmL1Translators.buildScenarioAndPush(
        container, annotation, ChaosEffect.spuriousWakeup());
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
