/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.grpc.testpack.l3.composers;

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
import com.macstab.chaos.grpc.testpack.l3.IncidentChaosGrpcGoawayStorm;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L3 composer for {@link IncidentChaosGrpcGoawayStorm}. */
@Slf4j
public final class GrpcGoawayStormComposer implements L3Composer<IncidentChaosGrpcGoawayStorm> {

    /** Public no-arg constructor required by the L3 composer contract. */
    public GrpcGoawayStormComposer() {}

    @Override
    public List<Object> apply(
            final GenericContainer<?> container, final IncidentChaosGrpcGoawayStorm annotation) {
        final List<Object> handles = new ArrayList<>();

        final RuleHandle recvResetHandle = CompositeConnectionChaos.standard().advanced()
                .apply(container, NetRule.errno(
                        Endpoint.wildcard(),
                        NetOperation.RECV,
                        Errno.ECONNRESET,
                        annotation.toxicity()));
        handles.add(recvResetHandle);

        final String scenarioId = JvmPlanAccumulator.instance()
                .mintScenarioId(IncidentChaosGrpcGoawayStorm.class.getSimpleName());
        final NamePattern cls = annotation.classPattern().isBlank()
                ? NamePattern.any()
                : NamePattern.prefix(annotation.classPattern());
        final ChaosScenario scenario = ChaosScenario.builder(scenarioId)
                .description("L3 gRPC GOAWAY storm — maxConnectionAge cycling UNAVAILABLE on in-flight streams")
                .selector(ChaosSelector.method(EnumSet.of(OperationType.METHOD_EXIT), cls, NamePattern.any()))
                .effect(ChaosEffect.injectException(
                        "io.grpc.StatusRuntimeException", "UNAVAILABLE: connection closed by GOAWAY"))
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
                    log.warn("GrpcGoawayStormComposer.removeAll: failed to remove connection rule", e);
                }
            } else if (h instanceof String scenarioId) {
                try {
                    JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
                } catch (final Exception e) {
                    log.warn("GrpcGoawayStormComposer.removeAll: failed to remove JVM scenario {}", scenarioId, e);
                }
            }
        }
    }

    @Override
    public List<String> describe(final IncidentChaosGrpcGoawayStorm annotation) {
        return List.of(
                "gRPC GOAWAY Storm — maxConnectionAge cycling causes UNAVAILABLE on in-flight streams",
                "connection: RECV ECONNRESET toxicity=" + annotation.toxicity(),
                "jvm: StatusRuntimeException(UNAVAILABLE) on class prefix '" + annotation.classPattern() + "' (METHOD_EXIT)",
                "severity=SEVERE — steady 10-20 UNAVAILABLE errors/hour; no transparent retry (grpc-java #9566)");
    }
}
