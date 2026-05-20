/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewMode;

/**
 * L1 translator for {@code @ChaosClockXxxSkew} annotations. Reads {@code skewMs} + {@code mode}
 * and builds a {@link ChaosEffect#skewClock(Duration, ClockSkewMode)}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ClockSkewTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ClockSkewTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final long skewMs = JvmL1Translators.readLong(annotation, "skewMs", -60_000L);
    final ClockSkewMode mode = JvmL1Translators.readEnum(annotation, "mode", ClockSkewMode.FIXED);
    return JvmL1Translators.buildScenarioAndPush(
        container, annotation, ChaosEffect.skewClock(Duration.ofMillis(skewMs), mode));
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
