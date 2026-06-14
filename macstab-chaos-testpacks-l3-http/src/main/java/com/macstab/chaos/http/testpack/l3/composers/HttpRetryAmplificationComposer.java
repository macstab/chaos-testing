/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.http.testpack.l3.composers;

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
import com.macstab.chaos.http.testpack.l3.IncidentChaosHttpRetryAmplification;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Composer for {@link IncidentChaosHttpRetryAmplification}.
 *
 * <p>Injects connection-refused errors and IOException at the HTTP client entry point to activate
 * retry logic and amplify load on the failing upstream.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class HttpRetryAmplificationComposer
    implements L3Composer<IncidentChaosHttpRetryAmplification> {

  public HttpRetryAmplificationComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosHttpRetryAmplification ann) {
    final List<Object> handles = new ArrayList<>();

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED, ann.toxicity())));

    final String scenarioId =
        JvmPlanAccumulator.instance().mintScenarioId("HttpRetryAmplification");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description(
                "HTTP retry amplification — inject IOException to trigger client retry storm")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.io.IOException", "HTTP upstream failure triggering retries"))
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
  public List<String> describe(final IncidentChaosHttpRetryAmplification ann) {
    return List.of(
        "HTTP Retry Amplification — retry storm turns partial failure into full saturation",
        "connection: CONNECT → ECONNREFUSED, toxicity=" + ann.toxicity(),
        "jvm: IOException(\"HTTP upstream failure triggering retries\") on class prefix '"
            + ann.classPattern()
            + "' (METHOD_ENTER)",
        "severity=CRITICAL — 50% error rate becomes 100% upstream saturation under retries");
  }
}
