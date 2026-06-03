/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.java.testpack.CompositeChaosMethodExceptionInjection;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.core.extension.L2Composer;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosMethodExceptionInjection}. */
@Slf4j
public final class MethodExceptionInjectionComposer
    implements L2Composer<CompositeChaosMethodExceptionInjection> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public MethodExceptionInjectionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container,
      final CompositeChaosMethodExceptionInjection annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosMethodExceptionInjection.class.getSimpleName());
    final NamePattern cls =
        annotation.classPattern().isBlank()
            ? NamePattern.any()
            : NamePattern.prefix(annotation.classPattern());
    final NamePattern mth =
        annotation.methodNamePattern().isBlank()
            ? NamePattern.any()
            : NamePattern.prefix(annotation.methodNamePattern());
    final ChaosSelector selector =
        ChaosSelector.method(EnumSet.of(OperationType.METHOD_ENTER), cls, mth);
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description("L2: method exception injection — "
                + annotation.exceptionClass() + " at METHOD_ENTER for class='"
                + annotation.classPattern() + "' method='" + annotation.methodNamePattern() + "'")
            .selector(selector)
            .effect(ChaosEffect.injectException(annotation.exceptionClass(),
                "injected by chaos L2 MethodExceptionInjection"))
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
          log.warn("MethodExceptionInjectionComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosMethodExceptionInjection annotation) {
    return List.of(
        "method exception injection — " + annotation.exceptionClass()
            + " thrown at METHOD_ENTER for class='" + annotation.classPattern()
            + "' method='" + annotation.methodNamePattern() + "'",
        "severity=MODERATE — scope determined by pattern breadth; narrow patterns for targeted faults");
  }
}
