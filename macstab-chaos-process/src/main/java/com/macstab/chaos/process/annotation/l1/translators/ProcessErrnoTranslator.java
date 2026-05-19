/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.process.CompositeProcessChaos;
import com.macstab.chaos.process.annotation.l1.ProcessErrnoBinding;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.model.ProcessRule;

/**
 * Parameterised L1 translator for every process-errno L1 annotation. Reads the per-annotation
 * {@link ProcessErrnoBinding} meta-annotation to recover the (selector, errno) tuple, then builds
 * and applies the corresponding {@link ProcessRule} via {@link AdvancedProcessChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ProcessErrnoTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ProcessErrnoTranslator() {}

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

  /**
   * Build the {@link ProcessRule} encoded by {@code annotation} without applying it.
   *
   * @throws IllegalStateException if {@link ProcessErrnoBinding} is missing
   */
  static ProcessRule buildRule(final Annotation annotation) {
    final ProcessErrnoBinding binding =
        annotation.annotationType().getAnnotation(ProcessErrnoBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@ProcessErrnoBinding meta-annotation missing on "
              + annotation.annotationType().getName()
              + " — every process-errno L1 annotation must declare its (selector, errno) tuple.");
    }
    final double probability = readProbability(annotation);
    return ProcessRule.errno(binding.selector(), binding.errno(), probability);
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
