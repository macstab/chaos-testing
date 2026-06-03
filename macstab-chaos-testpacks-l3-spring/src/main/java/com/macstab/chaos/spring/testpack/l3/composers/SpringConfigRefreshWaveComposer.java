/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.spring.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.spring.testpack.l3.IncidentChaosSpringConfigRefreshWave;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
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
 * Composer for {@link IncidentChaosSpringConfigRefreshWave}.
 *
 * <p>Applies RECV latency, DNS EAI_AGAIN, and a JVM BeanCreationException to reproduce the
 * compound failure profile of a simultaneous {@code /refresh} wave — every refreshing pod
 * drops in-flight requests, cascading if the config server is slow. (Spring Cloud Config #2341)
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpringConfigRefreshWaveComposer implements L3Composer<IncidentChaosSpringConfigRefreshWave> {

    public SpringConfigRefreshWaveComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosSpringConfigRefreshWave ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

        final var dns = CompositeDnsChaos.standard().advanced();
        handles.add(dns.apply(container,
                DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN)));

        final String id = JvmPlanAccumulator.instance().mintScenarioId("SpringConfigRefreshWave-exc");
        final var sc = ChaosScenario.builder(id)
                .description("Spring Config refresh wave — bean destruction cascade during simultaneous /refresh")
                .selector(ChaosSelector.method(
                        EnumSet.of(OperationType.METHOD_EXIT),
                        NamePattern.prefix(ann.classPattern()),
                        NamePattern.any()))
                .effect(ChaosEffect.injectException(
                        "org.springframework.beans.factory.BeanCreationException",
                        "Error creating bean with name"))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, sc));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosSpringConfigRefreshWave ann) {
        return List.of(
                "Spring Config Refresh Wave — simultaneous /refresh triggers cascading bean destruction",
                "connection: RECV latency=" + ann.latencyMs() + "ms (config server slow)",
                "dns: EAI_AGAIN (config server unreachable)",
                "jvm: BeanCreationException injection on '" + ann.classPattern() + "'",
                "severity=SEVERE — every refreshing pod drops in-flight requests; cascades if config server is slow (Spring Cloud Config #2341)");
    }
}
