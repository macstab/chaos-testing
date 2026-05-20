/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEffect.ReturnValueStrategy;

/**
 * L1 translator for {@code @ChaosMethodExitXxx} annotations. Builds a
 * {@link ChaosEffect#corruptReturnValue(ReturnValueStrategy)} from the annotation's
 * {@code strategy} attribute.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ReturnValueCorruptionTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ReturnValueCorruptionTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final ReturnValueStrategy strategy =
        JvmL1Translators.readEnum(annotation, "strategy", ReturnValueStrategy.NULL);
    return JvmL1Translators.buildScenarioAndPush(
        container, annotation, ChaosEffect.corruptReturnValue(strategy));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
