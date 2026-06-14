/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3.composers;

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
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.springboot.testpack.l3.IncidentChaosSpringDatabaseOutage;

/**
 * Composer for {@link IncidentChaosSpringDatabaseOutage}.
 *
 * <p>Applies connection ECONNREFUSED, transient DNS EAI_AGAIN, and a JVM
 * DataAccessResourceFailureException to reproduce the compound failure profile of a Spring Boot
 * service losing its database — the canonical trigger for Resilience4j circuit breaker opening and
 * readiness probe failure.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpringDatabaseOutageComposer
    implements L3Composer<IncidentChaosSpringDatabaseOutage> {

  public SpringDatabaseOutageComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosSpringDatabaseOutage ann) {
    final List<Object> handles = new ArrayList<>();

    final var conn = CompositeConnectionChaos.standard().advanced();
    handles.add(
        conn.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED, ann.toxicity())));

    final var dns = CompositeDnsChaos.standard().advanced();
    handles.add(dns.apply(container, DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN)));

    final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("SpringDatabaseOutage");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description("database connection lost — circuit breaker should open")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "org.springframework.dao.DataAccessResourceFailureException",
                    "database connection lost — circuit breaker should open"))
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
  public List<String> describe(final IncidentChaosSpringDatabaseOutage ann) {
    return List.of(
        "Spring Database Outage — DB gone, circuit breaker opens, pod removed from rotation",
        "connection: CONNECT → ECONNREFUSED, toxicity=" + ann.toxicity(),
        "dns: EAI_AGAIN on every forward lookup",
        "jvm: DataAccessResourceFailureException on class prefix '"
            + ann.classPattern()
            + "' (METHOD_ENTER)",
        "severity=CRITICAL — readiness probe fails, pod removed from load balancer rotation");
  }
}
