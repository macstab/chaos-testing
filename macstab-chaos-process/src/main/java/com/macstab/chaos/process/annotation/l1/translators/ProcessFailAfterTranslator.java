/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.process.CompositeProcessChaos;
import com.macstab.chaos.process.annotation.l1.ProcessFailAfterBinding;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.model.ProcessRule;

/**
 * Parameterised L1 translator for every process-fail-after L1 annotation. Reads the per-annotation
 * {@link ProcessFailAfterBinding} meta-annotation to recover the (selector, errno) tuple and the
 * annotation's {@code successesBeforeFailure} attribute to build the {@link ProcessRule}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ProcessFailAfterTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ProcessFailAfterTranslator() {}

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
    final ProcessFailAfterBinding binding =
        annotation.annotationType().getAnnotation(ProcessFailAfterBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@ProcessFailAfterBinding meta-annotation missing on "
              + annotation.annotationType().getName());
    }
    final long count = readSuccessesBeforeFailure(annotation);
    return ProcessRule.failAfter(binding.selector(), binding.errno(), count);
  }

  private static long readSuccessesBeforeFailure(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("successesBeforeFailure");
      final Object v = m.invoke(annotation);
      return v instanceof Long l ? l : 0L;
    } catch (final NoSuchMethodException e) {
      return 0L;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to read successesBeforeFailure() from " + annotation.annotationType().getName(), e);
    }
  }
}
