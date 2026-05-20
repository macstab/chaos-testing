/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.api.AdvancedTimeChaos;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeRule;

/**
 * Parameterised L1 translator for the time-offset L1 annotation family. Offset is libchaos-time's
 * unique third effect kind — it shifts the {@code struct timespec} returned by {@code
 * clock_gettime} by a configurable delta. Only valid on {@code CLOCK_GETTIME}.
 *
 * <p>This translator currently handles a single annotation ({@code ChaosClockGettimeOffset}), but
 * is kept parameterised so per-clock variants can be added later without changing the translator
 * wiring.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class TimeOffsetTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public TimeOffsetTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final TimeRule rule = buildRule(annotation);
    final AdvancedTimeChaos adv = CompositeTimeChaos.standard().advanced();
    return adv.apply(container, rule);
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    if (!(handle instanceof RuleHandle ruleHandle)) {
      return;
    }
    CompositeTimeChaos.standard().advanced().remove(container, ruleHandle);
  }

  static TimeRule buildRule(final Annotation annotation) {
    final long deltaMs = readDeltaMs(annotation);
    final double probability = readProbability(annotation);
    return TimeRule.offset(Duration.ofMillis(deltaMs), probability);
  }

  private static long readDeltaMs(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("deltaMs");
      final Object v = m.invoke(annotation);
      return v instanceof Long l ? l : -60_000L; // default: -60 s (clock-rewind)
    } catch (final NoSuchMethodException e) {
      return -60_000L;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to read deltaMs() from " + annotation.annotationType().getName(), e);
    }
  }

  private static double readProbability(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("probability");
      final Object v = m.invoke(annotation);
      return v instanceof Double d ? d : 1.0;
    } catch (final NoSuchMethodException e) {
      return 1.0;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to read probability() from " + annotation.annotationType().getName(), e);
    }
  }
}
