/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.testpack.l3.IncidentChaosJvmDeoptimizationStorm;

/**
 * Composer for {@link IncidentChaosJvmDeoptimizationStorm}.
 *
 * <p>Applies a SafepointStorm with class retransformation to reproduce the compound failure profile
 * of a JIT deoptimisation storm caused by JVMTI retransformation invalidating compiled code.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class JvmDeoptimizationStormComposer
    implements L3Composer<IncidentChaosJvmDeoptimizationStorm> {

  public JvmDeoptimizationStormComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosJvmDeoptimizationStorm ann) {
    final List<Object> handles = new ArrayList<>();

    final String id = JvmPlanAccumulator.instance().mintScenarioId("JvmDeoptimizationStorm");
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.SAFEPOINT_STORM))
            .effect(
                new ChaosEffect.SafepointStormEffect(
                    Duration.ofMillis(ann.gcIntervalMs()), ann.retransformClassCount()))
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
  public List<String> describe(final IncidentChaosJvmDeoptimizationStorm ann) {
    return List.of(
        "JVM JIT Deoptimization Storm — class retransformation invalidates compiled methods",
        "jvm: SafepointStorm every "
            + ann.gcIntervalMs()
            + "ms retransforming "
            + ann.retransformClassCount()
            + " classes per cycle",
        "severity=SEVERE — 2-5s CPU spike, 80% throughput drop; self-resolving after recompilation");
  }
}
