/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosSslHandshakeFailure;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosSslHandshakeFailure}. */
@Slf4j
public final class SslHandshakeFailureComposer implements L2Composer<CompositeChaosSslHandshakeFailure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public SslHandshakeFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosSslHandshakeFailure annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosSslHandshakeFailure.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.SSL.build(EnumSet.of(OperationType.SSL_HANDSHAKE));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: SSL handshake failure — SSLHandshakeException injected at probability "
                + annotation.probability())
            .selector(selector)
            .effect(ChaosEffect.injectException("javax.net.ssl.SSLHandshakeException",
                "injected TLS handshake failure by chaos L2"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    final String scenarioId = JvmPlanAccumulator.instance().addScenario(container, scenario);
    return List.of(scenarioId);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof String scenarioId) {
        try {
          JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
        } catch (final Exception e) {
          log.warn("SslHandshakeFailureComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosSslHandshakeFailure annotation) {
    return List.of(
        "SSL handshake failure — SSLHandshakeException injected at probability "
            + annotation.probability(),
        "severity=SEVERE — all HTTPS/mTLS connections fail; services using mTLS become simultaneously unreachable");
  }
}
