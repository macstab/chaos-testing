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
import com.macstab.chaos.grpc.testpack.l3.IncidentChaosGrpcConnectionDrain;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L3 composer for {@link IncidentChaosGrpcConnectionDrain}. */
@Slf4j
public final class GrpcConnectionDrainComposer implements L3Composer<IncidentChaosGrpcConnectionDrain> {

    /** Public no-arg constructor required by the L3 composer contract. */
    public GrpcConnectionDrainComposer() {}

    @Override
    public List<Object> apply(
            final GenericContainer<?> container, final IncidentChaosGrpcConnectionDrain annotation) {
        final List<Object> handles = new ArrayList<>();

        final RuleHandle resetHandle = CompositeConnectionChaos.standard().advanced()
                .apply(container, NetRule.errno(
                        Endpoint.wildcard(),
                        NetOperation.CONNECT,
                        Errno.ECONNRESET,
                        annotation.toxicity()));
        handles.add(resetHandle);

        final RuleHandle drainLagHandle = CompositeConnectionChaos.standard().advanced()
                .apply(container, NetRule.latency(
                        Endpoint.wildcard(),
                        NetOperation.SEND,
                        Duration.ofMillis(50L),
                        annotation.toxicity()));
        handles.add(drainLagHandle);

        final String scenarioId = JvmPlanAccumulator.instance()
                .mintScenarioId(IncidentChaosGrpcConnectionDrain.class.getSimpleName());
        final NamePattern cls = annotation.classPattern().isBlank()
                ? NamePattern.any()
                : NamePattern.prefix(annotation.classPattern());
        final ChaosScenario scenario = ChaosScenario.builder(scenarioId)
                .description("L3 gRPC connection drain — GOAWAY ECONNRESET + SEND drain-lag + UNAVAILABLE")
                .selector(ChaosSelector.method(EnumSet.of(OperationType.METHOD_ENTER), cls, NamePattern.any()))
                .effect(ChaosEffect.injectException(
                        "io.grpc.StatusRuntimeException", "UNAVAILABLE: server draining connection"))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        for (final Object h : handles) {
            if (h instanceof RuleHandle rh) {
                try {
                    new LibchaosTransport(LibchaosLib.NET).removeRules(container, rh.owner());
                } catch (final Exception e) {
                    log.warn("GrpcConnectionDrainComposer.removeAll: failed to remove connection rule", e);
                }
            } else if (h instanceof String scenarioId) {
                try {
                    JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
                } catch (final Exception e) {
                    log.warn("GrpcConnectionDrainComposer.removeAll: failed to remove JVM scenario {}", scenarioId, e);
                }
            }
        }
    }

    @Override
    public List<String> describe(final IncidentChaosGrpcConnectionDrain annotation) {
        return List.of(
                "L3 incident: gRPC connection drain — rolling deploy GOAWAY drain window",
                "connection: CONNECT ECONNRESET toxicity=" + annotation.toxicity() + " (GOAWAY frames)",
                "connection: SEND latency=50ms toxicity=" + annotation.toxicity() + " (drain-lag)",
                "jvm: injectException(io.grpc.StatusRuntimeException) on class='" + annotation.classPattern() + "'",
                "severity=SEVERE — clients must retry on new channel; channel warm-up extends unavailability");
    }
}
