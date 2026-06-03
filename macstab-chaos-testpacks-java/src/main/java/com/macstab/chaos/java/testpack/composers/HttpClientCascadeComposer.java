/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosHttpClientCascade;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosHttpClientCascade}. */
@Slf4j
public final class HttpClientCascadeComposer implements L2Composer<CompositeChaosHttpClientCascade> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public HttpClientCascadeComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosHttpClientCascade annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosHttpClientCascade.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.HTTP_CLIENT.build(EnumSet.of(OperationType.HTTP_CLIENT_SEND));
    final Duration delay = Duration.ofMillis(annotation.delayMs());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: HTTP client cascade — HttpClient.send() delayed by "
                + annotation.delayMs() + " ms")
            .selector(selector)
            .effect(ChaosEffect.delay(delay, delay))
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
          log.warn("HttpClientCascadeComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosHttpClientCascade annotation) {
    return List.of(
        "HTTP client cascade — every HttpClient.send() blocked for " + annotation.delayMs() + " ms",
        "severity=SEVERE — thread pools fill without a timeout shorter than delayMs; cascading failure");
  }
}
