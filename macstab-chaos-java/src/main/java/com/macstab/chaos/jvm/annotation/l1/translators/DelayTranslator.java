/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.jvm.api.ChaosEffect;

/**
 * L1 translator for all {@code @ChaosXxxDelay} interceptor annotations across every JVM selector
 * family. Reads {@code delayMs} (and optional {@code maxDelayMs}) attributes to build a
 * {@link ChaosEffect#delay(Duration, Duration)}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class DelayTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public DelayTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final long min = JvmL1Translators.readLong(annotation, "delayMs", 100L);
    final long max = JvmL1Translators.readLong(annotation, "maxDelayMs", min);
    final ChaosEffect effect =
        ChaosEffect.delay(Duration.ofMillis(min), Duration.ofMillis(Math.max(min, max)));
    return JvmL1Translators.buildScenarioAndPush(container, annotation, effect);
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    JvmL1Translators.removeScenario(container, handle);
  }
}
