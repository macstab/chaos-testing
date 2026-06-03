/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.testpack.l3.IncidentChaosJvmSafepointCascade;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Composer for {@link IncidentChaosJvmSafepointCascade}.
 *
 * <p>Applies a SafepointStorm stressor, RECV ECONNRESET connection errors, and DNS EAI_AGAIN to
 * reproduce the compound failure profile of a GC-pause-induced timeout cascade across all
 * connected systems.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class JvmSafepointCascadeComposer implements L3Composer<IncidentChaosJvmSafepointCascade> {

    public JvmSafepointCascadeComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosJvmSafepointCascade ann) {
        final List<Object> handles = new ArrayList<>();

        final String id = JvmPlanAccumulator.instance().mintScenarioId("JvmSafepointCascade");
        final ChaosScenario scenario = ChaosScenario.builder(id)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.SAFEPOINT_STORM))
                .effect(ChaosEffect.safepointStorm(Duration.ofMillis(ann.gcIntervalMs())))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

        final var conn = CompositeConnectionChaos.standard().advanced();
        handles.add(conn.apply(container,
                NetRule.errno(Endpoint.wildcard(), NetOperation.RECV, Errno.ECONNRESET, ann.toxicity())));

        final var dns = CompositeDnsChaos.standard().advanced();
        handles.add(dns.apply(container,
                DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN)));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosJvmSafepointCascade ann) {
        return List.of(
                "JVM Safepoint Cascade — GC pause triggers simultaneous timeout storm",
                "jvm: SafepointStorm every " + ann.gcIntervalMs() + "ms",
                "connection: RECV ECONNRESET toxicity=" + ann.toxicity() + " (HikariCP/Kafka/ZK timeouts cascade)",
                "dns: EAI_AGAIN (ZooKeeper session expires)",
                "severity=CRITICAL — one GC pause causes simultaneous failure across all dependent systems");
    }
}
