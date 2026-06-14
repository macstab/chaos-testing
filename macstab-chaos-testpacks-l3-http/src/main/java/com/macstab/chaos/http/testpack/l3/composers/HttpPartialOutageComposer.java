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
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;
import com.macstab.chaos.http.testpack.l3.IncidentChaosHttpPartialOutage;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Composer for {@link IncidentChaosHttpPartialOutage}.
 *
 * <p>Combines ETIMEDOUT on a fraction of connections, transient DNS EAI_AGAIN, and ConnectException
 * injection to simulate mixed healthy/failing backend conditions.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class HttpPartialOutageComposer implements L3Composer<IncidentChaosHttpPartialOutage> {

  public HttpPartialOutageComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosHttpPartialOutage ann) {
    final List<Object> handles = new ArrayList<>();

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(), NetOperation.CONNECT, Errno.ETIMEDOUT, ann.toxicity())));

    final var dns = CompositeDnsChaos.standard().advanced();
    handles.add(dns.apply(container, DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN)));

    final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("HttpPartialOutage");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description("HTTP partial outage — mixed healthy/failing backend connect exceptions")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.net.ConnectException", "Partial outage — mixed healthy/failing"))
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
  public List<String> describe(final IncidentChaosHttpPartialOutage ann) {
    return List.of(
        "HTTP Partial Outage — mixed healthy/failing backends with transient DNS failure",
        "connection: CONNECT → ETIMEDOUT, toxicity=" + ann.toxicity(),
        "dns: EAI_AGAIN on every forward lookup",
        "jvm: ConnectException(\"Partial outage — mixed healthy/failing\") on class prefix '"
            + ann.classPattern()
            + "' (METHOD_ENTER)",
        "severity=SEVERE — 50% error rate with mixed signals delays circuit-breaker activation");
  }
}
