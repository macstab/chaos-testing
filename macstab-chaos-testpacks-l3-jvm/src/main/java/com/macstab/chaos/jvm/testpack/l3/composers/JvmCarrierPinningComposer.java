/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.testpack.l3.IncidentChaosJvmCarrierPinning;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

/**
 * Composer for {@link IncidentChaosJvmCarrierPinning}.
 *
 * <p>Applies a VirtualThreadCarrierPinning stressor to reproduce the compound failure profile of
 * synchronized-block carrier pinning that starves the ForkJoinPool carrier pool in JDK 21+
 * services.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class JvmCarrierPinningComposer implements L3Composer<IncidentChaosJvmCarrierPinning> {

    public JvmCarrierPinningComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosJvmCarrierPinning ann) {
        final List<Object> handles = new ArrayList<>();

        final String id = JvmPlanAccumulator.instance().mintScenarioId("JvmCarrierPinning");
        final ChaosScenario scenario = ChaosScenario.builder(id)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
                .effect(ChaosEffect.virtualThreadCarrierPinning(ann.pinnedThreadCount(), Duration.ofMillis(ann.pinDurationMs())))
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
    public List<String> describe(final IncidentChaosJvmCarrierPinning ann) {
        return List.of(
                "JVM Virtual Thread Carrier Pinning — synchronized blocks starve carrier pool",
                "jvm: VirtualThreadCarrierPinning " + ann.pinnedThreadCount() + " carriers pinned for " + ann.pinDurationMs() + "ms each",
                "severity=CRITICAL — JDK 21+: service hangs with zero errors, zero rejections, zero warnings");
    }
}
