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
import com.macstab.chaos.memory.CompositeMemoryChaos;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;
import com.macstab.chaos.springboot.testpack.l3.IncidentChaosSpringStartupFailure;

/**
 * Composer for {@link IncidentChaosSpringStartupFailure}.
 *
 * <p>Applies DNS EAI_AGAIN, connection ECONNREFUSED, anonymous memory ENOMEM, and a JVM
 * ApplicationContextException to reproduce the compound failure profile of a Spring Boot pod
 * failing its startup probe in a Kubernetes environment under infrastructure unavailability.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpringStartupFailureComposer
    implements L3Composer<IncidentChaosSpringStartupFailure> {

  public SpringStartupFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosSpringStartupFailure ann) {
    final List<Object> handles = new ArrayList<>();

    final var dns = CompositeDnsChaos.standard().advanced();
    handles.add(dns.apply(container, DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN)));

    final var conn = CompositeConnectionChaos.standard().advanced();
    handles.add(
        conn.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED, ann.toxicity())));

    final var mem = CompositeMemoryChaos.standard().advanced();
    handles.add(
        mem.apply(
            container,
            MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM, ann.probability())));

    final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("SpringStartupFailure");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description("Spring context startup failure — startup probe will fail")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "org.springframework.context.ApplicationContextException",
                    "Spring context startup failure — startup probe will fail"))
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
  public List<String> describe(final IncidentChaosSpringStartupFailure ann) {
    return List.of(
        "Spring Startup Failure — Kubernetes startup probe failure loop under infrastructure unavailability",
        "dns: EAI_AGAIN on every forward lookup",
        "connection: CONNECT → ECONNREFUSED, toxicity=" + ann.toxicity(),
        "memory: MMAP_ANON → ENOMEM, probability=" + ann.probability(),
        "jvm: ApplicationContextException on class prefix '"
            + ann.classPattern()
            + "' (METHOD_ENTER)",
        "severity=CRITICAL — pod enters CrashLoopBackOff, rolling deploy stalls");
  }
}
