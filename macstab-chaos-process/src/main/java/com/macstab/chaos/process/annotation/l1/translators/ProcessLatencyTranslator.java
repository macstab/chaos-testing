/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.process.CompositeProcessChaos;
import com.macstab.chaos.process.annotation.l1.ProcessLatencyBinding;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.model.ProcessRule;

/**
 * Parameterised L1 translator for every process-latency L1 annotation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ProcessLatencyTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ProcessLatencyTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final ProcessRule rule = buildRule(annotation);
    final AdvancedProcessChaos adv = CompositeProcessChaos.standard().advanced();
    return adv.apply(container, rule);
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    if (!(handle instanceof RuleHandle ruleHandle)) {
      return;
    }
    CompositeProcessChaos.standard().advanced().remove(container, ruleHandle);
  }

  static ProcessRule buildRule(final Annotation annotation) {
    final ProcessLatencyBinding binding =
        annotation.annotationType().getAnnotation(ProcessLatencyBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@ProcessLatencyBinding meta-annotation missing on "
              + annotation.annotationType().getName()
              + " — every process-latency L1 annotation must declare its selector.");
    }
    return ProcessRule.latency(binding.selector(), Duration.ofMillis(readDelayMs(annotation)));
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
