/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.http.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.http.testpack.l3.IncidentChaosHttpCascadingTimeout;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Composer for {@link IncidentChaosHttpCascadingTimeout}.
 *
 * <p>Applies TCP-level timeout, half-duration DNS latency, and SocketTimeoutException injection to
 * reproduce a multi-hop timeout cascade across a service call chain.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class HttpCascadingTimeoutComposer
    implements L3Composer<IncidentChaosHttpCascadingTimeout> {

  public HttpCascadingTimeoutComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosHttpCascadingTimeout ann) {
    final List<Object> handles = new ArrayList<>();

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.timeout(Endpoint.wildcard(), Duration.ofMillis(ann.latencyMs()), 1.0)));

    final var dns = CompositeDnsChaos.standard().advanced();
    handles.add(
        dns.apply(
            container,
            DnsRule.latency(DnsSelector.anyForward(), Duration.ofMillis(ann.latencyMs() / 2))));

    final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("HttpCascadingTimeout");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description(
                "HTTP cascading timeout — inject SocketTimeoutException on HTTP client methods")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.net.SocketTimeoutException", "HTTP timeout cascade"))
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
  public List<String> describe(final IncidentChaosHttpCascadingTimeout ann) {
    return List.of(
        "HTTP Cascading Timeout — timeout cascade across service call chain",
        "connection: TCP timeout " + ann.latencyMs() + "ms (100% toxicity)",
        "dns: forward lookup latency " + (ann.latencyMs() / 2) + "ms",
        "jvm: SocketTimeoutException(\"HTTP timeout cascade\") on class prefix '"
            + ann.classPattern()
            + "' (METHOD_ENTER)",
        "severity=CRITICAL — thread pool saturation across entire downstream dependency graph");
  }
}
