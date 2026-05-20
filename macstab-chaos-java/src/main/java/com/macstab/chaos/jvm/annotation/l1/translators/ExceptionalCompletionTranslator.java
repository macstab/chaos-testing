/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEffect.FailureKind;

/**
 * L1 translator for {@code @ChaosAsyncCompleteXxx} annotations. Reads {@code failureKind} +
 * {@code message} and builds a {@link ChaosEffect#exceptionalCompletion(FailureKind, String)}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ExceptionalCompletionTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ExceptionalCompletionTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final FailureKind kind = JvmL1Translators.readEnum(annotation, "failureKind", FailureKind.RUNTIME);
    final String message =
        JvmL1Translators.readString(annotation, "message", "completed exceptionally by chaos L1");
    return JvmL1Translators.buildScenarioAndPush(
        container, annotation, ChaosEffect.exceptionalCompletion(kind, message));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
