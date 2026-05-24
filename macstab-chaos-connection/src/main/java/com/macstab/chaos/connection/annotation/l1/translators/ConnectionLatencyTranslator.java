/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.connection.api.AdvancedConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L1Translator;

/**
 * Parameterised L1 translator for every connection-latency L1 annotation. Endpoint is always {@link
 * Endpoint#wildcard()} at the L1 tier.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ConnectionLatencyTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ConnectionLatencyTranslator() {}

  @Override
  public Object apply(final GenericContainer<?> container, final Annotation annotation) {
    final NetRule rule = buildRule(annotation);
    final AdvancedConnectionChaos adv = CompositeConnectionChaos.standard().advanced();
    return adv.apply(container, rule);
  }

  @Override
  public void remove(final GenericContainer<?> container, final Object handle) {
    if (!(handle instanceof RuleHandle ruleHandle)) {
      return;
    }
    new LibchaosTransport(LibchaosLib.NET).removeRules(container, ruleHandle.owner());
  }

  static NetRule buildRule(final Annotation annotation) {
    final ConnectionLatencyBinding binding =
        annotation.annotationType().getAnnotation(ConnectionLatencyBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@ConnectionLatencyBinding meta-annotation missing on "
              + annotation.annotationType().getName());
    }
    return NetRule.latency(
        Endpoint.wildcard(),
        binding.operation(),
        Duration.ofMillis(readDelayMs(annotation)),
        readToxicity(annotation));
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

  private static double readToxicity(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("toxicity");
      final Object v = m.invoke(annotation);
      return v instanceof Double d ? d : 1.0;
    } catch (final NoSuchMethodException e) {
      return 1.0;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to read toxicity() from " + annotation.annotationType().getName(), e);
    }
  }
}
