/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3.composers;

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
import com.macstab.chaos.system.testpack.l3.IncidentChaosSystemDirectMemoryLeak;

/**
 * Composer for {@link IncidentChaosSystemDirectMemoryLeak}.
 *
 * <p>Applies a DirectBufferPressure stressor and {@code OutOfMemoryError} injection on {@code
 * io.netty} classes to reproduce the compound failure profile of Netty/gRPC off-heap ByteBuffer
 * exhaustion where the heap is clean but every new NIO allocation fails.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SystemDirectMemoryLeakComposer
    implements L3Composer<IncidentChaosSystemDirectMemoryLeak> {

  public SystemDirectMemoryLeakComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosSystemDirectMemoryLeak ann) {
    final List<Object> handles = new ArrayList<>();

    final String directId =
        JvmPlanAccumulator.instance().mintScenarioId("SystemDirectMemoryLeak-direct");
    final ChaosScenario directScenario =
        ChaosScenario.builder(directId)
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
            .effect(
                ChaosEffect.directBufferPressure(
                    (long) ann.totalMb() * 1024L * 1024L, ann.bufferSizeMb() * 1024 * 1024))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, directScenario));

    final String oomId = JvmPlanAccumulator.instance().mintScenarioId("SystemDirectMemoryLeak-oom");
    final ChaosScenario oomScenario =
        ChaosScenario.builder(oomId)
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_EXIT),
                    NamePattern.prefix("io.netty"),
                    NamePattern.any()))
            .effect(
                ChaosEffect.injectException("java.lang.OutOfMemoryError", "Direct buffer memory"))
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
  public List<String> describe(final IncidentChaosSystemDirectMemoryLeak ann) {
    return List.of(
        "System Direct Memory Leak — Netty/gRPC off-heap exhaustion; heap clean, pod 'alive'",
        "jvm: DirectBufferPressure "
            + ann.totalMb()
            + "MB in "
            + ann.bufferSizeMb()
            + "MB chunks (no Cleaner)",
        "jvm: OutOfMemoryError('Direct buffer memory') injection on io.netty classes",
        "severity=SEVERE — heap is clean; liveness probes pass; every new NIO/Netty/gRPC allocation fails");
  }
}
