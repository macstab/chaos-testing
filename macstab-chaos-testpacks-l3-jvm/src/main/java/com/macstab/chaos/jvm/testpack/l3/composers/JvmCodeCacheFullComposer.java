/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.testpack.l3.IncidentChaosJvmCodeCacheFull;

/**
 * Composer for {@link IncidentChaosJvmCodeCacheFull}.
 *
 * <p>Applies a CodeCachePressure stressor to reproduce the compound failure profile of JIT code
 * cache exhaustion causing permanent interpreter fallback and throughput collapse.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class JvmCodeCacheFullComposer implements L3Composer<IncidentChaosJvmCodeCacheFull> {

  public JvmCodeCacheFullComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosJvmCodeCacheFull ann) {
    final List<Object> handles = new ArrayList<>();

    final String id = JvmPlanAccumulator.instance().mintScenarioId("JvmCodeCacheFull");
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.CODE_CACHE_PRESSURE))
            .effect(ChaosEffect.codeCachePressure(ann.classCount(), ann.methodsPerClass()))
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
  public List<String> describe(final IncidentChaosJvmCodeCacheFull ann) {
    return List.of(
        "JVM Code Cache Full — JIT compiler disabled permanently",
        "jvm: CodeCachePressure "
            + ann.classCount()
            + " classes × "
            + ann.methodsPerClass()
            + " methods (JIT-compiled 15,000×)",
        "severity=CRITICAL — 10-50x throughput drop; zero exceptions thrown; accumulates over days (Atlassian Confluence)");
  }
}
