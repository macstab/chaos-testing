/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.memory.CompositeMemoryChaos;
import com.macstab.chaos.memory.annotation.l1.MemoryLatencyBinding;
import com.macstab.chaos.memory.api.AdvancedMemoryChaos;
import com.macstab.chaos.memory.api.RuleHandle;
import com.macstab.chaos.memory.model.MemoryRule;

/**
 * Parameterised L1 translator for every memory-latency L1 annotation. Reads the per-annotation
 * {@link MemoryLatencyBinding} meta-annotation to recover the selector, then builds and applies
 * the corresponding {@link MemoryRule} via {@link AdvancedMemoryChaos}.
 *
 * <p>One translator instance handles all 7 memory-latency L1 annotations.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class MemoryLatencyTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the reflective L1 translator contract. */
  public MemoryLatencyTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final MemoryRule rule = buildRule(annotation);
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    return adv.apply(container, rule);
  }

  /**
   * Build the {@link MemoryRule} encoded by {@code annotation} without applying it.
   *
   * @param annotation any L1 annotation carrying {@link MemoryLatencyBinding}
   * @return the rule the translator would apply
   * @throws IllegalStateException if {@link MemoryLatencyBinding} is missing
   */
  static MemoryRule buildRule(final Annotation annotation) {
    final MemoryLatencyBinding binding =
        annotation.annotationType().getAnnotation(MemoryLatencyBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@MemoryLatencyBinding meta-annotation missing on "
              + annotation.annotationType().getName()
              + " — every memory-latency L1 annotation must declare its selector.");
    }
    final Duration delay = Duration.ofMillis(readDelayMs(annotation));
    return MemoryRule.latency(binding.selector(), delay);
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    if (!(handle instanceof RuleHandle ruleHandle)) {
      return;
    }
    CompositeMemoryChaos.standard().advanced().remove(container, ruleHandle);
  }

  private static long readDelayMs(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("delayMs");
      final Object v = m.invoke(annotation);
      return v instanceof Long l ? l : 50L;
    } catch (final NoSuchMethodException e) {
      return 50L;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to read delayMs() from " + annotation.annotationType().getName(), e);
    }
  }
}
