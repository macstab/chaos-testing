/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosNativeLibraryLoadFailure;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosNativeLibraryLoadFailure}. */
@Slf4j
public final class NativeLibraryLoadFailureComposer
    implements L2Composer<CompositeChaosNativeLibraryLoadFailure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public NativeLibraryLoadFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container,
      final CompositeChaosNativeLibraryLoadFailure annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosNativeLibraryLoadFailure.class.getSimpleName());
    final ChaosSelector selector =
        JvmSelectorKind.JVM_RUNTIME.build(EnumSet.of(OperationType.NATIVE_LIBRARY_LOAD));
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: native library load failure — UnsatisfiedLinkError injected at probability "
                    + annotation.probability())
            .selector(selector)
            .effect(
                ChaosEffect.injectException(
                    "java.lang.UnsatisfiedLinkError",
                    "injected native library load failure by chaos L2"))
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
          log.warn(
              "NativeLibraryLoadFailureComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosNativeLibraryLoadFailure annotation) {
    return List.of(
        "native library load failure — UnsatisfiedLinkError injected on System.loadLibrary() at probability "
            + annotation.probability(),
        "severity=SEVERE — JNI failure in static block makes class permanently unloadable; native-accelerated code falls back to Java");
  }
}
