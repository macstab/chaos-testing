/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.feign.testpack.l3.IncidentChaosFeignHystrixThreadLeak;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Composer for {@link IncidentChaosFeignHystrixThreadLeak}.
 *
 * <p>Injects RECV latency to trigger Hystrix timeouts and injects HystrixTimeoutException
 * at METHOD_EXIT to activate the fallback, while the real thread remains blocked in
 * {@code socketRead0()} — reproducing the Netflix/Hystrix #1240 thread-leak pattern.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class FeignHystrixThreadLeakComposer implements L3Composer<IncidentChaosFeignHystrixThreadLeak> {

    public FeignHystrixThreadLeakComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosFeignHystrixThreadLeak ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

        final String id = JvmPlanAccumulator.instance().mintScenarioId("FeignHystrixThreadLeak-exc");
        final var scenario = ChaosScenario.builder(id)
                .description("Feign + Hystrix Thread Leak — fallback fires but thread blocked in socketRead0() forever")
                .selector(ChaosSelector.method(
                        EnumSet.of(OperationType.METHOD_EXIT),
                        NamePattern.prefix(ann.classPattern()),
                        NamePattern.any()))
                .effect(ChaosEffect.injectException(
                        "com.netflix.hystrix.exception.HystrixTimeoutException", "Hystrix timeout"))
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
    public List<String> describe(final IncidentChaosFeignHystrixThreadLeak ann) {
        return List.of(
                "Feign + Hystrix Thread Leak — fallback fires but thread blocked in socketRead0() forever",
                "connection: RECV latency=" + ann.latencyMs() + "ms (Hystrix timeout trigger)",
                "jvm: HystrixTimeoutException injection on '" + ann.classPattern() + "'",
                "severity=CRITICAL — pool drains thread-by-thread; no recovery without restart (Netflix/Hystrix #1240)");
    }
}
