/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.grpc.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;
import com.macstab.chaos.grpc.testpack.l3.IncidentChaosGrpcLoadBalancingFailure;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L3 composer for {@link IncidentChaosGrpcLoadBalancingFailure}. */
@Slf4j
public final class GrpcLoadBalancingFailureComposer implements L3Composer<IncidentChaosGrpcLoadBalancingFailure> {

    /** Public no-arg constructor required by the L3 composer contract. */
    public GrpcLoadBalancingFailureComposer() {}

    @Override
    public List<Object> apply(
            final GenericContainer<?> container, final IncidentChaosGrpcLoadBalancingFailure annotation) {
        final List<Object> handles = new ArrayList<>();

        final com.macstab.chaos.dns.api.RuleHandle dnsEaiHandle = CompositeDnsChaos.standard().advanced()
                .apply(container, DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN));
        handles.add(dnsEaiHandle);

        final com.macstab.chaos.dns.api.RuleHandle dnsLatencyHandle = CompositeDnsChaos.standard().advanced()
                .apply(container, DnsRule.latency(DnsSelector.anyForward(), Duration.ofMillis(500L)));
        handles.add(dnsLatencyHandle);

        final RuleHandle connHandle = CompositeConnectionChaos.standard().advanced()
                .apply(container, NetRule.errno(
                        Endpoint.wildcard(),
                        NetOperation.CONNECT,
                        Errno.ECONNREFUSED,
                        annotation.toxicity()));
        handles.add(connHandle);

        final String scenarioId = JvmPlanAccumulator.instance()
                .mintScenarioId(IncidentChaosGrpcLoadBalancingFailure.class.getSimpleName());
        final NamePattern cls = annotation.classPattern().isBlank()
                ? NamePattern.any()
                : NamePattern.prefix(annotation.classPattern());
        final ChaosScenario scenario = ChaosScenario.builder(scenarioId)
                .description("L3 gRPC LB failure — EAI_AGAIN + DNS latency + ECONNREFUSED + UNAVAILABLE")
                .selector(ChaosSelector.method(EnumSet.of(OperationType.METHOD_ENTER), cls, NamePattern.any()))
                .effect(ChaosEffect.injectException(
                        "io.grpc.StatusRuntimeException", "UNAVAILABLE: load balancer name resolution failure"))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        for (final Object h : handles) {
            if (h instanceof com.macstab.chaos.dns.api.RuleHandle dnsRh) {
                try {
                    new LibchaosTransport(LibchaosLib.DNS).removeRules(container, dnsRh.owner());
                } catch (final Exception e) {
                    log.warn("GrpcLoadBalancingFailureComposer.removeAll: failed to remove DNS rule", e);
                }
            } else if (h instanceof RuleHandle netRh) {
                try {
                    new LibchaosTransport(LibchaosLib.NET).removeRules(container, netRh.owner());
                } catch (final Exception e) {
                    log.warn("GrpcLoadBalancingFailureComposer.removeAll: failed to remove connection rule", e);
                }
            } else if (h instanceof String scenarioId) {
                try {
                    JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
                } catch (final Exception e) {
                    log.warn("GrpcLoadBalancingFailureComposer.removeAll: failed to remove JVM scenario {}", scenarioId, e);
                }
            }
        }
    }

    @Override
    public List<String> describe(final IncidentChaosGrpcLoadBalancingFailure annotation) {
        return List.of(
                "L3 incident: gRPC load balancing failure — Kubernetes DNS flap during rolling update",
                "dns: EAI_AGAIN on anyForward() — name resolver retry storm",
                "dns: latency=500ms on anyForward() — slow SRV record resolution",
                "connection: CONNECT ECONNREFUSED toxicity=" + annotation.toxicity() + " — backends unreachable",
                "jvm: injectException(io.grpc.StatusRuntimeException) on class='" + annotation.classPattern() + "'",
                "severity=SEVERE — channel enters TRANSIENT_FAILURE; channel warm-up extends unavailability");
    }
}
