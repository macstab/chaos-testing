/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.translators;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding;
import com.macstab.chaos.connection.api.AdvancedConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L1Translator;

/**
 * Parameterised L1 translator for every connection-errno L1 annotation. Endpoint is always {@link
 * Endpoint#wildcard()} at the L1 tier.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ConnectionErrnoTranslator implements L1Translator<Annotation> {

  /** Public no-arg constructor required by the L1 translator contract. */
  public ConnectionErrnoTranslator() {}

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
    CompositeConnectionChaos.standard().advanced().remove(container, ruleHandle);
  }

  static NetRule buildRule(final Annotation annotation) {
    final ConnectionErrnoBinding binding =
        annotation.annotationType().getAnnotation(ConnectionErrnoBinding.class);
    if (binding == null) {
      throw new IllegalStateException(
          "@ConnectionErrnoBinding meta-annotation missing on "
              + annotation.annotationType().getName());
    }
    return NetRule.errno(
        Endpoint.wildcard(), binding.operation(), binding.errno(), readToxicity(annotation));
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
