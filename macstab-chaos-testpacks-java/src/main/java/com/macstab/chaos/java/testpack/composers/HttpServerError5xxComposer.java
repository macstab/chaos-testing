/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosHttpServerError5xx;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosHttpServerError5xx}. */
@Slf4j
public final class HttpServerError5xxComposer
    implements L2Composer<CompositeChaosHttpServerError5xx> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public HttpServerError5xxComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosHttpServerError5xx annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosHttpServerError5xx.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.HTTP_CLIENT.build(EnumSet.of(OperationType.HTTP_CLIENT_SEND));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: HTTP 5xx error — IOException injected at probability "
                    + annotation.probability())
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.io.IOException", "injected HTTP 5xx error by chaos L2"))
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
          log.warn("HttpServerError5xxComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosHttpServerError5xx annotation) {
    return List.of(
        "HTTP 5xx error — IOException injected into HttpClient.send() at probability "
            + annotation.probability(),
        "severity=MODERATE — callers with retry recover; callers without retry surface the error immediately");
  }
}
