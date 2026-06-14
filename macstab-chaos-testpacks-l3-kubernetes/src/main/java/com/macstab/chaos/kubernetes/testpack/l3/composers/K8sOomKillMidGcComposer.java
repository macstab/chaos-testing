/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kubernetes.testpack.l3.composers;

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
import com.macstab.chaos.kubernetes.testpack.l3.IncidentChaosK8sOomKillMidGc;
import com.macstab.chaos.memory.CompositeMemoryChaos;
import com.macstab.chaos.memory.api.AdvancedMemoryChaos;

/**
 * Composer for {@link IncidentChaosK8sOomKillMidGc}.
 *
 * <p>Combines an OOM-kill stressor with a JVM-level OutOfMemoryError injection to reproduce the
 * compound failure profile of a cgroup RSS breach during a G1 heap evacuation pause.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class K8sOomKillMidGcComposer implements L3Composer<IncidentChaosK8sOomKillMidGc> {

  public K8sOomKillMidGcComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosK8sOomKillMidGc ann) {
    final List<Object> handles = new ArrayList<>();

    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    handles.add(adv.simulateOomKiller(container, ann.toxicity()));

    final String id = JvmPlanAccumulator.instance().mintScenarioId("K8sOomKillMidGc-oom");
    final ChaosScenario sc =
        ChaosScenario.builder(id)
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_EXIT),
                    NamePattern.prefix("com."),
                    NamePattern.any()))
            .effect(ChaosEffect.injectException("java.lang.OutOfMemoryError", "Java heap space"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, sc));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosK8sOomKillMidGc ann) {
    return List.of(
        "K8s OOM Kill Mid-GC — cgroup RSS limit hit during G1 evacuation → exit 137",
        "memory: OOM-kill toxicity=" + ann.toxicity() + " (cgroup RSS limit breach)",
        "jvm: OutOfMemoryError injection on application classes",
        "severity=CRITICAL — pod killed with no Java OOM in logs; GC log shows 'GC overhead limit exceeded' then silence");
  }
}
