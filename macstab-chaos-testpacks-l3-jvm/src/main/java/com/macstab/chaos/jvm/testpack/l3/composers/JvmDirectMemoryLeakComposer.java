/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.testpack.l3.IncidentChaosJvmDirectMemoryLeak;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

/**
 * Composer for {@link IncidentChaosJvmDirectMemoryLeak}.
 *
 * <p>Applies a DirectBufferPressure stressor to reproduce the compound failure profile of
 * Netty/gRPC off-heap ByteBuffer exhaustion where the heap remains clean but every NIO
 * allocation fails.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class JvmDirectMemoryLeakComposer implements L3Composer<IncidentChaosJvmDirectMemoryLeak> {

    public JvmDirectMemoryLeakComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosJvmDirectMemoryLeak ann) {
        final List<Object> handles = new ArrayList<>();

        final String id = JvmPlanAccumulator.instance().mintScenarioId("JvmDirectMemoryLeak");
        final ChaosScenario scenario = ChaosScenario.builder(id)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
                .effect(ChaosEffect.directBufferPressure(ann.totalMb() * 1024L * 1024L, ann.bufferSizeMb() * 1024 * 1024))
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
    public List<String> describe(final IncidentChaosJvmDirectMemoryLeak ann) {
        return List.of(
                "JVM Direct Memory Leak — Netty/gRPC off-heap ByteBuffer exhaustion",
                "jvm: DirectBufferPressure " + ann.totalMb() + "MB in " + ann.bufferSizeMb() + "MB buffers (no Cleaner, leak mode)",
                "severity=SEVERE — heap clean; pod alive to probes; every new NIO/Netty operation fails");
    }
}
