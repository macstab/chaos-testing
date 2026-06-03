/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.testpack.l3.IncidentChaosJvmGcLockerFakeOom;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Composer for {@link IncidentChaosJvmGcLockerFakeOom}.
 *
 * <p>Applies GcPressure, MonitorContention, and an OOM injection to reproduce the compound
 * failure profile of a GCLocker-induced spurious OutOfMemoryError where the heap is not
 * actually exhausted.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class JvmGcLockerFakeOomComposer implements L3Composer<IncidentChaosJvmGcLockerFakeOom> {

    public JvmGcLockerFakeOomComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosJvmGcLockerFakeOom ann) {
        final List<Object> handles = new ArrayList<>();

        final String gcId = JvmPlanAccumulator.instance().mintScenarioId("JvmGcLockerFakeOom-gc");
        final ChaosScenario gcScenario = ChaosScenario.builder(gcId)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.GC_PRESSURE))
                .effect(ChaosEffect.gcPressure(ann.allocationRateMbPerSec() * 1024L * 1024L, Duration.ofSeconds(30)))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, gcScenario));

        final String monitorId = JvmPlanAccumulator.instance().mintScenarioId("JvmGcLockerFakeOom-monitor");
        final ChaosScenario monitorScenario = ChaosScenario.builder(monitorId)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.MONITOR_CONTENTION))
                .effect(ChaosEffect.monitorContention(Duration.ofMillis(ann.lockHoldMs()), ann.contendingThreads()))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, monitorScenario));

        final String oomId = JvmPlanAccumulator.instance().mintScenarioId("JvmGcLockerFakeOom-oom");
        final ChaosScenario oomScenario = ChaosScenario.builder(oomId)
                .description("GCLocker fake OOM — OutOfMemoryError: GC overhead limit exceeded")
                .selector(ChaosSelector.method(
                        EnumSet.of(OperationType.METHOD_EXIT),
                        NamePattern.prefix("com."),
                        NamePattern.any()))
                .effect(ChaosEffect.injectException("java.lang.OutOfMemoryError", "GC overhead limit exceeded"))
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
    public List<String> describe(final IncidentChaosJvmGcLockerFakeOom ann) {
        return List.of(
                "JVM GCLocker Fake OOM — monitor contention blocks GC causing spurious OutOfMemoryError",
                "jvm: GcPressure " + ann.allocationRateMbPerSec() + "MB/s + MonitorContention " + ann.contendingThreads() + " threads × " + ann.lockHoldMs() + "ms",
                "jvm: OutOfMemoryError injection (GC overhead limit exceeded) on application classes",
                "severity=SEVERE — heap not exhausted; restart fixes it; root cause never found (CleverTap incident)");
    }
}
