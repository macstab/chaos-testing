/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.api.AdvancedConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L1Translator;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;

/**
 * L1 translator for {@code ChaosPollTimeout} — implicitly bound to {@code POLL} (the only
 * NetOperation for which TIMEOUT is meaningful).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ConnectionTimeoutTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ConnectionTimeoutTranslator() {}

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
    return NetRule.timeout(
        Endpoint.wildcard(),
        Duration.ofMillis(readTimeoutMs(annotation)),
        readToxicity(annotation));
  }

  private static long readTimeoutMs(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("timeoutMs");
      final Object v = m.invoke(annotation);
      return v instanceof Long l ? l : 5000L;
    } catch (final NoSuchMethodException e) {
      return 5000L;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to read timeoutMs() from " + annotation.annotationType().getName(), e);
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
