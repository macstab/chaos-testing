/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding;
import com.macstab.chaos.filesystem.api.AdvancedFilesystemChaos;
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

/**
 * Parameterised L1 translator for every IO-latency L1 annotation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class IoLatencyTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public IoLatencyTranslator() {}

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
    new LibchaosTransport(LibchaosLib.IO).removeRules(container, ruleHandle.owner());
  }

  static IoRule buildRule(final Annotation annotation) {
    final IoLatencyBinding binding =
        annotation.annotationType().getAnnotation(IoLatencyBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@IoLatencyBinding meta-annotation missing on " + annotation.annotationType().getName());
    }
    return IoRule.latency(
        PathPrefix.wildcard(), binding.operation(), Duration.ofMillis(readDelayMs(annotation)));
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
