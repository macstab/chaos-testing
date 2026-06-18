/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.springboot.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.process.CompositeProcessChaos;
import com.macstab.chaos.process.model.ProcessRule;
import com.macstab.chaos.process.model.ProcessSelector;
import com.macstab.chaos.springboot.testpack.l3.IncidentChaosSpringGracefulShutdown;

/**
 * Composer for {@link IncidentChaosSpringGracefulShutdown}.
 *
 * <p>Injects an async request timeout exception, adds RECV latency to extend the drain window, and
 * adds PTHREAD_CREATE latency to delay thread join during executor shutdown — reproducing the
 * compound delay that causes the Kubernetes grace period to expire during a rolling deploy.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpringGracefulShutdownComposer
    implements L3Composer<IncidentChaosSpringGracefulShutdown> {

  public SpringGracefulShutdownComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosSpringGracefulShutdown ann) {
    final List<Object> handles = new ArrayList<>();

    final String scenarioId =
        JvmPlanAccumulator.instance().mintScenarioId("SpringGracefulShutdown");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description("active request timeout during shutdown drain")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "org.springframework.web.context.request.async.AsyncRequestTimeoutException",
                    "active request timeout during shutdown drain"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

    final var conn = CompositeConnectionChaos.standard().advanced();
    handles.add(
        conn.apply(
            container,
            NetRule.latency(
                Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.drainMs()), 1.0)));

    final var proc = CompositeProcessChaos.standard().advanced();
    handles.add(
        proc.apply(
            container,
            ProcessRule.latency(
                ProcessSelector.PTHREAD_CREATE, Duration.ofMillis(ann.drainMs() / 2))));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosSpringGracefulShutdown ann) {
    return List.of(
        "Spring Graceful Shutdown — rolling deploy drain window exceeds Kubernetes grace period",
        "jvm: AsyncRequestTimeoutException on class prefix '"
            + ann.classPattern()
            + "' (METHOD_ENTER)",
        "connection: RECV latency " + ann.drainMs() + "ms (in-flight requests)",
        "process: PTHREAD_CREATE latency " + (ann.drainMs() / 2) + "ms (thread join lag)",
        "severity=SEVERE — active connections aborted, rolling deploy disrupts live traffic");
  }
}
