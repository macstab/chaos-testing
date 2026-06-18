/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3.composers;

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
import com.macstab.chaos.redis.testpack.l3.IncidentChaosRedisSlowlog;

/**
 * Composer for {@link IncidentChaosRedisSlowlog}.
 *
 * <p>Injects RECV-path latency and SocketTimeoutException at METHOD_ENTER on Redis client methods
 * to reproduce the application-level experience of a Redis slow-log command backlog.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisSlowlogComposer implements L3Composer<IncidentChaosRedisSlowlog> {

  public RedisSlowlogComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosRedisSlowlog ann) {
    final List<Object> handles = new ArrayList<>();

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.latency(
                Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

    final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("RedisSlowlog");
    final var selector =
        ChaosSelector.method(
            EnumSet.of(OperationType.METHOD_ENTER),
            NamePattern.prefix(ann.classPattern()),
            NamePattern.any());
    final var scenario =
        ChaosScenario.builder(scenarioId)
            .description(
                "Redis slow-log backlog — inject SocketTimeoutException on Redis client entry")
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.net.SocketTimeoutException", "Redis command timeout"))
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
  public List<String> describe(final IncidentChaosRedisSlowlog ann) {
    return List.of(
        "Redis Slow-log Cascade — command backlog causing client-side timeouts",
        "connection: RECV latency " + ann.latencyMs() + "ms (100% toxicity)",
        "jvm: SocketTimeoutException(\"Redis command timeout\") on class prefix '"
            + ann.classPattern()
            + "' (METHOD_ENTER)",
        "severity=MODERATE — connection-pool saturation and elevated p99 latency");
  }
}
