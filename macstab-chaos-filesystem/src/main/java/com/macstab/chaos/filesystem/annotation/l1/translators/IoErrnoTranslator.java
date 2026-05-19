/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.api.AdvancedFilesystemChaos;
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

/**
 * Parameterised L1 translator for every IO-errno L1 annotation. PathPrefix is always
 * {@link PathPrefix#wildcard()} at the L1 tier.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class IoErrnoTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public IoErrnoTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final IoRule rule = buildRule(annotation);
    final AdvancedFilesystemChaos adv = CompositeFilesystemChaos.standard().advanced();
    return adv.apply(container, rule);
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    if (!(handle instanceof RuleHandle ruleHandle)) {
      return;
    }
    CompositeFilesystemChaos.standard().advanced().remove(container, ruleHandle);
  }

  static IoRule buildRule(final Annotation annotation) {
    final IoErrnoBinding binding =
        annotation.annotationType().getAnnotation(IoErrnoBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@IoErrnoBinding meta-annotation missing on " + annotation.annotationType().getName());
    }
    return IoRule.errno(
        PathPrefix.wildcard(), binding.operation(), binding.errno(), readProbability(annotation));
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
