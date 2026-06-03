/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kubernetes.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.kubernetes.testpack.l3.IncidentChaosK8sCpuThrottleGcAmplification;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

/**
 * Composer for {@link IncidentChaosK8sCpuThrottleGcAmplification}.
 *
 * <p>Combines a SafepointStorm JVM stressor with RECV timeout injection to reproduce the compound
 * failure where CFS CPU throttling extends GC safepoint pauses into liveness-probe-killing
 * multi-hundred-millisecond windows.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class K8sCpuThrottleGcAmplificationComposer implements L3Composer<IncidentChaosK8sCpuThrottleGcAmplification> {

    public K8sCpuThrottleGcAmplificationComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosK8sCpuThrottleGcAmplification ann) {
        final List<Object> handles = new ArrayList<>();

        final String id = JvmPlanAccumulator.instance().mintScenarioId("K8sCpuThrottleGcAmplification");
        final ChaosScenario sc = ChaosScenario.builder(id)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.SAFEPOINT_STORM))
                .effect(ChaosEffect.safepointStorm(Duration.ofMillis(ann.gcIntervalMs())))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, sc));

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofSeconds(30), ann.toxicity())));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosK8sCpuThrottleGcAmplification ann) {
        return List.of(
                "K8s CPU Throttle GC Amplification — CFS throttle turns 50ms GC into 400ms → liveness kill",
                "jvm: SafepointStorm every " + ann.gcIntervalMs() + "ms",
                "connection: RECV timeout toxicity=" + ann.toxicity(),
                "severity=CRITICAL — 71% of k8s deployments with CPU limits experience this");
    }
}
