/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

/**
 * Verifies the accumulator's rollback semantic — when {@code applyPlan} fails (mock container,
 * agent not loaded, file-system error), the failed scenario is removed from the active set
 * <em>before</em> the exception propagates. Without this guarantee the next add would re-push the
 * same broken plan and either repeat the error or silently activate a scenario the caller thought
 * failed.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("JvmPlanAccumulator — rollback on push failure")
class JvmPlanAccumulatorTest {

  @Test
  @DisplayName("addScenario rolls back when applyPlan throws (mock container)")
  void rollsBackOnPushFailure() {
    final GenericContainer<?> container = Mockito.mock(GenericContainer.class);
    Mockito.when(container.getDockerImageName()).thenReturn("mock:test");

    final ChaosScenario scenario =
        ChaosScenario.builder("rollback-probe")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
            .effect(ChaosEffect.heapPressure(1024L, 256))
            .activationPolicy(ActivationPolicy.always())
            .build();

    // applyPlan will throw because the mock container has no real ChaosAgentTransport behind it.
    assertThatThrownBy(() -> JvmPlanAccumulator.instance().addScenario(container, scenario))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JVM chaos plan");

    // Critical: the active set for this container must be empty after the failure.
    assertThat(JvmPlanAccumulator.instance().activeScenarios(container))
        .as(
            "accumulator must roll back the failed scenario; otherwise next add re-pushes the broken plan")
        .isEmpty();
  }
}
