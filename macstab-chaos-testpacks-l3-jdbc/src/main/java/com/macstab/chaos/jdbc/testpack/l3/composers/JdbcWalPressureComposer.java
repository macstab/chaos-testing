/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3.composers;

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
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.jdbc.testpack.l3.IncidentChaosJdbcWalPressure;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L3 composer for {@link IncidentChaosJdbcWalPressure}. */
@Slf4j
public final class JdbcWalPressureComposer implements L3Composer<IncidentChaosJdbcWalPressure> {

  /** Public no-arg constructor required by the L3 composer contract. */
  public JdbcWalPressureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosJdbcWalPressure annotation) {
    final List<Object> handles = new ArrayList<>();

    final PathPrefix dataPath = PathPrefix.path(annotation.dataPath());
    final Duration fsyncDelay = Duration.ofMillis(annotation.fsyncDelayMs());
    final Duration writeDelay = Duration.ofMillis(annotation.fsyncDelayMs() / 2);

    final com.macstab.chaos.filesystem.api.RuleHandle fsyncHandle =
        CompositeFilesystemChaos.standard()
            .advanced()
            .apply(container, IoRule.latency(dataPath, IoOperation.FSYNC, fsyncDelay));
    handles.add(fsyncHandle);

    final com.macstab.chaos.filesystem.api.RuleHandle writeHandle =
        CompositeFilesystemChaos.standard()
            .advanced()
            .apply(container, IoRule.latency(dataPath, IoOperation.WRITE, writeDelay));
    handles.add(writeHandle);

    final RuleHandle connHandle =
        CompositeConnectionChaos.standard()
            .advanced()
            .apply(
                container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, fsyncDelay, 1.0));
    handles.add(connHandle);

    final String scenarioId =
        JvmPlanAccumulator.instance()
            .mintScenarioId(IncidentChaosJdbcWalPressure.class.getSimpleName());
    final NamePattern cls =
        annotation.classPattern().isBlank()
            ? NamePattern.any()
            : NamePattern.prefix(annotation.classPattern());
    final ChaosScenario scenario =
        ChaosScenario.builder(scenarioId)
            .description(
                "L3 JDBC WAL pressure — FSYNC/WRITE latency + RECV back-pressure + SQLTimeoutException")
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_ENTER), cls, NamePattern.any()))
            .effect(
                ChaosEffect.injectException(
                    "java.sql.SQLTimeoutException", "WAL sync timeout exceeded"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof com.macstab.chaos.filesystem.api.RuleHandle ioRh) {
        try {
          new LibchaosTransport(LibchaosLib.IO).removeRules(container, ioRh.owner());
        } catch (final Exception e) {
          log.warn("JdbcWalPressureComposer.removeAll: failed to remove filesystem rule", e);
        }
      } else if (h instanceof RuleHandle netRh) {
        try {
          new LibchaosTransport(LibchaosLib.NET).removeRules(container, netRh.owner());
        } catch (final Exception e) {
          log.warn("JdbcWalPressureComposer.removeAll: failed to remove connection rule", e);
        }
      } else if (h instanceof String scenarioId) {
        try {
          JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
        } catch (final Exception e) {
          log.warn(
              "JdbcWalPressureComposer.removeAll: failed to remove JVM scenario {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final IncidentChaosJdbcWalPressure annotation) {
    return List.of(
        "L3 incident: JDBC WAL pressure — fsync delay causing commit timeout and replica lag",
        "filesystem: FSYNC latency="
            + annotation.fsyncDelayMs()
            + "ms on path="
            + annotation.dataPath(),
        "filesystem: WRITE latency="
            + (annotation.fsyncDelayMs() / 2)
            + "ms on path="
            + annotation.dataPath(),
        "connection: RECV latency=" + annotation.fsyncDelayMs() + "ms toxicity=1.0",
        "jvm: injectException(java.sql.SQLTimeoutException) on class='"
            + annotation.classPattern()
            + "'",
        "severity=SEVERE — commit latency spikes; replica lag accumulates; storage tuning required");
  }
}
