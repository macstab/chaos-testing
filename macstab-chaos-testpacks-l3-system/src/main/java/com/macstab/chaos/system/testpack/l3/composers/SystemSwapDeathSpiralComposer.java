/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.system.testpack.l3.IncidentChaosSystemSwapDeathSpiral;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

/**
 * Composer for {@link IncidentChaosSystemSwapDeathSpiral}.
 *
 * <p>Applies heap pressure, GC pressure, and filesystem read latency to reproduce the
 * compound failure profile of the swap death spiral: heap swapped to disk, GC forced to
 * traverse swapped pages producing a 45-second STW pause, liveness kill, repeat every 3 min.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SystemSwapDeathSpiralComposer implements L3Composer<IncidentChaosSystemSwapDeathSpiral> {

    public SystemSwapDeathSpiralComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosSystemSwapDeathSpiral ann) {
        final List<Object> handles = new ArrayList<>();

        final String heapId = JvmPlanAccumulator.instance().mintScenarioId("SystemSwapDeathSpiral-heap");
        final ChaosScenario heapScenario = ChaosScenario.builder(heapId)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
                .effect(ChaosEffect.heapPressure(ann.heapFillMb() * 1024L * 1024L, 1024 * 1024))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, heapScenario));

        final String gcId = JvmPlanAccumulator.instance().mintScenarioId("SystemSwapDeathSpiral-gc");
        final ChaosScenario gcScenario = ChaosScenario.builder(gcId)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.GC_PRESSURE))
                .effect(ChaosEffect.gcPressure(ann.allocationRateMbPerSec() * 1024L * 1024L, Duration.ofSeconds(30)))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, gcScenario));

        final var fsAdv = CompositeFilesystemChaos.standard().advanced();
        handles.add(fsAdv.apply(container,
                IoRule.latency(PathPrefix.wildcard(), IoOperation.READ, Duration.ofSeconds(10))));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosSystemSwapDeathSpiral ann) {
        return List.of(
                "System Swap Death Spiral — heap swapped out; GC traversal causes 45s STW → liveness kill → repeat",
                "jvm: HeapPressure " + ann.heapFillMb() + "MB + GcPressure " + ann.allocationRateMbPerSec() + "MB/s",
                "filesystem: READ latency 10s (page-fault simulation for swapped pages)",
                "severity=CRITICAL — repeats every 3 min; appears as periodic pod restarts; root cause is swap");
    }
}
