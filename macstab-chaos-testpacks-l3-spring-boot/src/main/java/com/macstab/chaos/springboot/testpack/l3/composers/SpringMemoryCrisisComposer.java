/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3.composers;

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
import com.macstab.chaos.memory.CompositeMemoryChaos;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;
import com.macstab.chaos.springboot.testpack.l3.IncidentChaosSpringMemoryCrisis;

/**
 * Composer for {@link IncidentChaosSpringMemoryCrisis}.
 *
 * <p>Fails both anonymous and file-backed mmap allocations at the configured rate and injects an
 * OutOfMemoryError via the JVM chaos layer to reproduce the compound failure profile of a Spring
 * Boot service exhausting its heap just before an OOM kill.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpringMemoryCrisisComposer
    implements L3Composer<IncidentChaosSpringMemoryCrisis> {

  public SpringMemoryCrisisComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosSpringMemoryCrisis ann) {
    final List<Object> handles = new ArrayList<>();

    final var mem = CompositeMemoryChaos.standard().advanced();
    handles.add(
        mem.apply(
            container,
            MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM, ann.pressureRate())));
    handles.add(
        mem.apply(
            container,
            MemoryRule.errno(MemorySelector.MMAP, MmapErrno.ENOMEM, ann.pressureRate())));

    final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("SpringMemoryCrisis");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description("heap exhaustion — OOM kill imminent")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.lang.OutOfMemoryError", "heap exhaustion — OOM kill imminent"))
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
  public List<String> describe(final IncidentChaosSpringMemoryCrisis ann) {
    return List.of(
        "Spring Memory Crisis — sustained leak drives OOM kill, circuit breaker opens",
        "memory: MMAP_ANON → ENOMEM, rate=" + ann.pressureRate(),
        "memory: MMAP → ENOMEM, rate=" + ann.pressureRate() + " (file-backed mappings)",
        "jvm: OutOfMemoryError on class prefix '" + ann.classPattern() + "' (METHOD_ENTER)",
        "severity=CRITICAL — pod OOM killed, in-flight requests lost");
  }
}
