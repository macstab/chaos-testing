/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.springboot.testpack.l3.IncidentChaosSpringConfigServerDown;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetRule;
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

/**
 * Composer for {@link IncidentChaosSpringConfigServerDown}.
 *
 * <p>Applies DNS EAI_FAIL, a connection timeout, and a JVM ConnectException to reproduce the
 * compound failure profile of a Spring Cloud Config Server outage with stale-config fallback.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpringConfigServerDownComposer implements L3Composer<IncidentChaosSpringConfigServerDown> {

    public SpringConfigServerDownComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosSpringConfigServerDown ann) {
        final List<Object> handles = new ArrayList<>();

        final var dns = CompositeDnsChaos.standard().advanced();
        handles.add(dns.apply(container,
                DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_FAIL)));

        final var conn = CompositeConnectionChaos.standard().advanced();
        handles.add(conn.apply(container,
                NetRule.timeout(Endpoint.wildcard(), Duration.ofMillis(ann.timeoutMs()), ann.toxicity())));

        final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("SpringConfigServerDown");
        final var selector = ChaosSelector.method(
                EnumSet.of(OperationType.METHOD_ENTER),
                NamePattern.prefix(ann.classPattern()),
                NamePattern.any());
        final var scenario = ChaosScenario.builder(scenarioId)
                .description("Spring Cloud Config server unreachable — using stale config")
                .selector(selector)
                .effect(ChaosEffect.injectException(
                        "java.net.ConnectException",
                        "Spring Cloud Config server unreachable — using stale config"))
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
    public List<String> describe(final IncidentChaosSpringConfigServerDown ann) {
        return List.of(
                "Spring Config Server Down — refresh fails, stale config persists, feature flag divergence",
                "dns: EAI_FAIL on every forward lookup (hard DNS failure)",
                "connection: timeout after " + ann.timeoutMs() + "ms, toxicity=" + ann.toxicity(),
                "jvm: ConnectException on class prefix '" + ann.classPattern() + "' (METHOD_ENTER)",
                "severity=SEVERE — config divergence between pods; fail-fast → CrashLoopBackOff");
    }
}
