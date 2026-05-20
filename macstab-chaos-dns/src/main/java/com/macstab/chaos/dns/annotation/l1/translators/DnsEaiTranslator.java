/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.annotation.l1.translators;

import java.lang.annotation.Annotation;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.annotation.l1.DnsEaiBinding;
import com.macstab.chaos.dns.api.AdvancedDnsChaos;
import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.DnsRule;

/**
 * Parameterised L1 translator for every DNS-EAI L1 annotation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class DnsEaiTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public DnsEaiTranslator() {}

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
    final DnsEaiBinding binding = annotation.annotationType().getAnnotation(DnsEaiBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@DnsEaiBinding meta-annotation missing on " + annotation.annotationType().getName());
    }
    return DnsRule.eai(binding.selectorKind().toSelector(), binding.errno());
  }
}
