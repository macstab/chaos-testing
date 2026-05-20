/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;

/**
 * L1 translator for {@code @ChaosXxxGate} interceptor annotations. Builds a
 * {@link ChaosEffect#gate(Duration)} from the annotation's {@code maxBlockMs} attribute.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class GateTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public GateTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final long maxBlockMs = JvmL1Translators.readLong(annotation, "maxBlockMs", 30_000L);
    return JvmL1Translators.buildScenarioAndPush(
        container, annotation, ChaosEffect.gate(Duration.ofMillis(maxBlockMs)));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
