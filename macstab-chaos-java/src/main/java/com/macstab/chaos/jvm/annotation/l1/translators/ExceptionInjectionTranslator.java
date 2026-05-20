/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;

/**
 * L1 translator for {@code @ChaosXxxInjectException} interceptor annotations. Builds a
 * {@link ChaosEffect#injectException(String, String)} from the annotation's
 * {@code exceptionClassName} + {@code message} attributes.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ExceptionInjectionTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ExceptionInjectionTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final String cls =
        JvmL1Translators.readString(annotation, "exceptionClassName", "java.io.IOException");
    final String message =
        JvmL1Translators.readString(annotation, "message", "injected by chaos L1");
    final ChaosEffect effect = ChaosEffect.injectException(cls, message);
    return JvmL1Translators.isMethodBinding(annotation)
        ? JvmL1Translators.buildMethodScenarioAndPush(container, annotation, effect)
        : JvmL1Translators.buildScenarioAndPush(container, annotation, effect);
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
