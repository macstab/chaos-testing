/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosJmxInvocationStorm;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosJmxInvocationStorm}. */
@Slf4j
public final class JmxInvocationStormComposer
    implements L2Composer<CompositeChaosJmxInvocationStorm> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public JmxInvocationStormComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosJmxInvocationStorm annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosJmxInvocationStorm.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.JVM_RUNTIME.build(EnumSet.of(OperationType.JMX_INVOKE));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: JMX invocation storm — ReflectionException injected at probability "
                    + annotation.probability())
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "javax.management.ReflectionException",
                    "injected JMX invocation failure by chaos L2"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    final String scenarioId = JvmPlanAccumulator.instance().addScenario(container, scenario);
    return List.of(scenarioId);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof String scenarioId) {
        try {
          JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
        } catch (final Exception e) {
          log.warn("JmxInvocationStormComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosJmxInvocationStorm annotation) {
    return List.of(
        "JMX invocation storm — ReflectionException injected on MBeanServer.invoke() at probability "
            + annotation.probability(),
        "severity=MILD — monitoring gaps; application code using JMX for config/ops will fail");
  }
}
