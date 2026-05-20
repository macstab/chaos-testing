/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.annotation.l1.TimeErrnoBinding;
import com.macstab.chaos.time.api.AdvancedTimeChaos;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeRule;

/**
 * Parameterised L1 translator for every time-errno L1 annotation. Reads the per-annotation {@link
 * TimeErrnoBinding} meta-annotation and constructs the corresponding {@link TimeRule}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class TimeErrnoTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public TimeErrnoTranslator() {}

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
    final TimeErrnoBinding binding =
        annotation.annotationType().getAnnotation(TimeErrnoBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@TimeErrnoBinding meta-annotation missing on " + annotation.annotationType().getName());
    }
    return TimeRule.errno(binding.selector(), binding.errno(), readProbability(annotation));
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
