/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.annotation.l1.TimeLatencyBinding;
import com.macstab.chaos.time.api.AdvancedTimeChaos;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeRule;

/**
 * Parameterised L1 translator for every time-latency L1 annotation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class TimeLatencyTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public TimeLatencyTranslator() {}

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
    final TimeLatencyBinding binding =
        annotation.annotationType().getAnnotation(TimeLatencyBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@TimeLatencyBinding meta-annotation missing on "
              + annotation.annotationType().getName());
    }
    return TimeRule.latency(binding.selector(), Duration.ofMillis(readDelayMs(annotation)));
  }

  private static long readDelayMs(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("delayMs");
      final Object v = m.invoke(annotation);
      return v instanceof Long l ? l : 10L;
    } catch (final NoSuchMethodException e) {
      return 10L;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to read delayMs() from " + annotation.annotationType().getName(), e);
    }
  }
}
