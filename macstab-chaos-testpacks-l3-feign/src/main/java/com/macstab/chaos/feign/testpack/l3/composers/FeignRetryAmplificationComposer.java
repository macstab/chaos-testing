/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3.composers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.feign.testpack.l3.IncidentChaosFeignRetryAmplification;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Composer for {@link IncidentChaosFeignRetryAmplification}.
 *
 * <p>Injects ECONNREFUSED on CONNECT to trigger brownout conditions, then injects IOException at
 * METHOD_EXIT on the Feign client to activate both the Feign retry layer and any enclosing
 * Resilience4j retry, producing up to 9× upstream amplification.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class FeignRetryAmplificationComposer
    implements L3Composer<IncidentChaosFeignRetryAmplification> {

  public FeignRetryAmplificationComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosFeignRetryAmplification ann) {
    final List<Object> handles = new ArrayList<>();

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED, ann.toxicity())));

    final String id = JvmPlanAccumulator.instance().mintScenarioId("FeignRetryAmplification-exc");
    final var scenario =
        ChaosScenario.builder(id)
            .description(
                "Feign Retry Amplification — Feign × Resilience4j = 9 upstream calls per user request")
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_EXIT),
                    NamePattern.prefix(ann.classPattern()),
                    NamePattern.any()))
            .effect(ChaosEffect.injectException("java.io.IOException", "Connection refused"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosFeignRetryAmplification ann) {
    return List.of(
        "Feign Retry Amplification — Feign × Resilience4j = 9 upstream calls per user request",
        "connection: CONNECT ECONNREFUSED toxicity=" + ann.toxicity(),
        "jvm: IOException injection on '" + ann.classPattern() + "' (retry trigger)",
        "severity=SEVERE — 9× upstream amplification during brownout can cascade downstream service");
  }
}
