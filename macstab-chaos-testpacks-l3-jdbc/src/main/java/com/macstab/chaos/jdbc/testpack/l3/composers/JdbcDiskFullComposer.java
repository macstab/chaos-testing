/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3.composers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.jdbc.testpack.l3.IncidentChaosJdbcDiskFull;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L3 composer for {@link IncidentChaosJdbcDiskFull}. */
@Slf4j
public final class JdbcDiskFullComposer implements L3Composer<IncidentChaosJdbcDiskFull> {

  /** Public no-arg constructor required by the L3 composer contract. */
  public JdbcDiskFullComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosJdbcDiskFull annotation) {
    final List<Object> handles = new ArrayList<>();

    final PathPrefix dataPath = PathPrefix.path(annotation.path());

    final com.macstab.chaos.filesystem.api.RuleHandle writeHandle =
        CompositeFilesystemChaos.standard()
            .advanced()
            .apply(
                container,
                IoRule.errno(dataPath, IoOperation.WRITE, Errno.ENOSPC, annotation.probability()));
    handles.add(writeHandle);

    final com.macstab.chaos.filesystem.api.RuleHandle fsyncHandle =
        CompositeFilesystemChaos.standard()
            .advanced()
            .apply(
                container,
                IoRule.errno(dataPath, IoOperation.FSYNC, Errno.ENOSPC, annotation.probability()));
    handles.add(fsyncHandle);

    final String scenarioId =
        JvmPlanAccumulator.instance()
            .mintScenarioId(IncidentChaosJdbcDiskFull.class.getSimpleName());
    final NamePattern cls =
        annotation.classPattern().isBlank()
            ? NamePattern.any()
            : NamePattern.prefix(annotation.classPattern());
    final ChaosScenario scenario =
        ChaosScenario.builder(scenarioId)
            .description("L3 JDBC disk full — ENOSPC on WRITE + FSYNC + disk-full exception")
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_ENTER), cls, NamePattern.any()))
            .effect(
                ChaosEffect.injectException("java.sql.SQLException", "disk full — write failed"))
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
          log.warn("JdbcDiskFullComposer.removeAll: failed to remove filesystem rule", e);
        }
      } else if (h instanceof String scenarioId) {
        try {
          JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
        } catch (final Exception e) {
          log.warn(
              "JdbcDiskFullComposer.removeAll: failed to remove JVM scenario {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final IncidentChaosJdbcDiskFull annotation) {
    return List.of(
        "L3 incident: JDBC disk full — ENOSPC during bulk write or migration",
        "filesystem: WRITE ENOSPC probability="
            + annotation.probability()
            + " on path="
            + annotation.path(),
        "filesystem: FSYNC ENOSPC probability="
            + annotation.probability()
            + " on path="
            + annotation.path(),
        "jvm: injectException(java.sql.SQLException) on class='" + annotation.classPattern() + "'",
        "severity=SEVERE — partial data and corruption risk; volume expansion required to recover");
  }
}
