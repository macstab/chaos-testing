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
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.grpc.testpack.l3.IncidentChaosGrpcDeadlinePropagation;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeRule;

import lombok.extern.slf4j.Slf4j;

/** L3 composer for {@link IncidentChaosGrpcDeadlinePropagation}. */
@Slf4j
public final class GrpcDeadlinePropagationComposer
    implements L3Composer<IncidentChaosGrpcDeadlinePropagation> {

  /** Public no-arg constructor required by the L3 composer contract. */
  public GrpcDeadlinePropagationComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosGrpcDeadlinePropagation annotation) {
    final List<Object> handles = new ArrayList<>();

    final RuleHandle connHandle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.latency(
                    Endpoint.wildcard(),
                    NetOperation.RECV,
                    Duration.ofMillis(annotation.latencyMs()),
                    1.0));
    handles.add(connHandle);

    final com.macstab.chaos.time.api.RuleHandle timeHandle =
        CompositeTimeChaos.standard()
            .advanced()
            .apply(
                container,
                TimeRule.offset(TimeClock.MONOTONIC, Duration.ofMillis(-annotation.skewMs()), 1.0));
    handles.add(timeHandle);

    final String scenarioId =
        JvmPlanAccumulator.instance()
            .mintScenarioId(IncidentChaosGrpcDeadlinePropagation.class.getSimpleName());
    final NamePattern cls =
        annotation.classPattern().isBlank()
            ? NamePattern.any()
            : NamePattern.prefix(annotation.classPattern());
    final ChaosScenario scenario =
        ChaosScenario.builder(scenarioId)
            .description(
                "L3 gRPC deadline propagation — RECV latency + monotonic skew + DEADLINE_EXCEEDED")
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_ENTER), cls, NamePattern.any()))
            .effect(
                ChaosEffect.injectException(
                    "io.grpc.StatusRuntimeException",
                    "DEADLINE_EXCEEDED: deadline propagation failure"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof RuleHandle netRh) {
        try {
          new LibchaosTransport(LibchaosLib.NET).removeRules(container, netRh.owner());
        } catch (final Exception e) {
          log.warn(
              "GrpcDeadlinePropagationComposer.removeAll: failed to remove connection rule", e);
        }
      } else if (h instanceof com.macstab.chaos.time.api.RuleHandle timeRh) {
        try {
          new LibchaosTransport(LibchaosLib.TIME).removeRules(container, timeRh.owner());
        } catch (final Exception e) {
          log.warn("GrpcDeadlinePropagationComposer.removeAll: failed to remove time rule", e);
        }
      } else if (h instanceof String scenarioId) {
        try {
          JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
        } catch (final Exception e) {
          log.warn(
              "GrpcDeadlinePropagationComposer.removeAll: failed to remove JVM scenario {}",
              scenarioId,
              e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final IncidentChaosGrpcDeadlinePropagation annotation) {
    return List.of(
        "L3 incident: gRPC deadline propagation cascade",
        "connection: RECV latency=" + annotation.latencyMs() + "ms toxicity=1.0",
        "time: MONOTONIC offset=-" + annotation.skewMs() + "ms probability=1.0 (slow clock)",
        "jvm: injectException(io.grpc.StatusRuntimeException) on class='"
            + annotation.classPattern()
            + "'",
        "severity=CRITICAL — entire call chain receives DEADLINE_EXCEEDED; retry storms compound latency");
  }
}
