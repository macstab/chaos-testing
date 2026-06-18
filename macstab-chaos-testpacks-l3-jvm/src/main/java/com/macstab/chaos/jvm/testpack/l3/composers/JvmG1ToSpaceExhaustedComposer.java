/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.testpack.l3.IncidentChaosJvmG1ToSpaceExhausted;

/**
 * Composer for {@link IncidentChaosJvmG1ToSpaceExhausted}.
 *
 * <p>Applies HeapPressure, GcPressure, and an OutOfMemoryError injection to reproduce the compound
 * failure profile of G1 GC to-space exhaustion under sustained heap pressure with
 * liveness-probe-killing full STW pauses.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class JvmG1ToSpaceExhaustedComposer
    implements L3Composer<IncidentChaosJvmG1ToSpaceExhausted> {

  public JvmG1ToSpaceExhaustedComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosJvmG1ToSpaceExhausted ann) {
    final List<Object> handles = new ArrayList<>();

    final String heapId =
        JvmPlanAccumulator.instance().mintScenarioId("JvmG1ToSpaceExhausted-heap");
    final ChaosScenario heapScenario =
        ChaosScenario.builder(heapId)
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
            .effect(ChaosEffect.heapPressure(ann.heapFillMb() * 1024L * 1024L, 1024 * 1024))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, heapScenario));

    final String gcId = JvmPlanAccumulator.instance().mintScenarioId("JvmG1ToSpaceExhausted-gc");
    final ChaosScenario gcScenario =
        ChaosScenario.builder(gcId)
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.GC_PRESSURE))
            .effect(
                ChaosEffect.gcPressure(
                    ann.allocationRateMbPerSec() * 1024L * 1024L, Duration.ofSeconds(30)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, gcScenario));

    final String oomId = JvmPlanAccumulator.instance().mintScenarioId("JvmG1ToSpaceExhausted-oom");
    final ChaosScenario oomScenario =
        ChaosScenario.builder(oomId)
            .description("G1 to-space exhausted — OutOfMemoryError: Java heap space")
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_EXIT),
                    NamePattern.prefix("com."),
                    NamePattern.any()))
            .effect(ChaosEffect.injectException("java.lang.OutOfMemoryError", "Java heap space"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, oomScenario));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosJvmG1ToSpaceExhausted ann) {
    return List.of(
        "JVM G1 GC To-Space Exhausted — full STW evacuation failure under heap pressure",
        "jvm: HeapPressure "
            + ann.heapFillMb()
            + "MB retained + GcPressure "
            + ann.allocationRateMbPerSec()
            + "MB/s",
        "jvm: OutOfMemoryError injection on application classes (METHOD_EXIT)",
        "severity=CRITICAL — GC pause 5-30x longer than normal; liveness probe kills pod mid-GC");
  }
}
