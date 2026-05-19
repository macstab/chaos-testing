/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.annotation.l1.DnsLatencyBinding;
import com.macstab.chaos.dns.api.AdvancedDnsChaos;
import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.DnsRule;

/**
 * Parameterised L1 translator for every DNS-latency L1 annotation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class DnsLatencyTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public DnsLatencyTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final DnsRule rule = buildRule(annotation);
    final AdvancedDnsChaos adv = CompositeDnsChaos.standard().advanced();
    return adv.apply(container, rule);
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    if (!(handle instanceof RuleHandle ruleHandle)) {
      return;
    }
    CompositeDnsChaos.standard().advanced().remove(container, ruleHandle);
  }

  static DnsRule buildRule(final Annotation annotation) {
    final DnsLatencyBinding binding =
        annotation.annotationType().getAnnotation(DnsLatencyBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@DnsLatencyBinding meta-annotation missing on " + annotation.annotationType().getName());
    }
    return DnsRule.latency(
        binding.selectorKind().toSelector(), Duration.ofMillis(readDelayMs(annotation)));
  }

  private static long readDelayMs(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("delayMs");
      final Object v = m.invoke(annotation);
      return v instanceof Long l ? l : 100L;
    } catch (final NoSuchMethodException e) {
      return 100L;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to read delayMs() from " + annotation.annotationType().getName(), e);
    }
  }
}
