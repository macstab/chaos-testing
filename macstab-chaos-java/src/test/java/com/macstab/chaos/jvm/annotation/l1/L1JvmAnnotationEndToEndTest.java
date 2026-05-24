/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.extension.ChaosApplicationReport;
import com.macstab.chaos.core.extension.L1AnnotationProcessor;
import com.macstab.chaos.core.extension.L1AnnotationProcessor.AppliedL1;
import com.macstab.chaos.core.extension.L1AnnotationProcessor.ContainerHandle;
import com.macstab.chaos.jvm.JavaAgentTransport;
import com.macstab.chaos.jvm.annotation.l1.async.ChaosAsyncCancelDelay;
import com.macstab.chaos.jvm.annotation.l1.async.ChaosAsyncCompleteSuppress;

/**
 * End-to-end integration test for the JVM L1 annotation path.
 *
 * <p>Exercises the full chain: L1 annotation on a fixture class → {@link L1AnnotationProcessor}
 * reflective dispatch → effect translator → {@code JvmPlanAccumulator} → plan JSON written to the
 * container's {@code /etc/chaos/plan.json}. Verifies that {@code applyClassLevel} populates the
 * plan file and that {@code removeAll} clears it back to the empty-plan sentinel.
 *
 * <p>We do not exercise the agent's runtime instrumentation here — the agent project's own
 * integration tests cover that surface. This test verifies the <em>delivery contract</em> through
 * the L1 annotation tier.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("L1 JVM annotations — end-to-end annotation path")
class L1JvmAnnotationEndToEndTest {

  private static final String JRE_IMAGE = "eclipse-temurin:21-jre";
  private static final String PLAN_PATH = "/etc/chaos/plan.json";
  private static final String EMPTY_PLAN = "{\"scenarios\":[]}";

  // ==================== Fixture classes ====================

  @ChaosAsyncCancelDelay
  static class WithAsyncCancelDelay {}

  @ChaosAsyncCompleteSuppress
  static class WithAsyncCompleteSuppress {}

  @ChaosAsyncCancelDelay
  @ChaosAsyncCompleteSuppress
  static class WithTwoAnnotations {}

  // ==================== Tests ====================

  @Test
  @DisplayName(
      "@ChaosAsyncCancelDelay writes scenario to plan.json and removeAll clears it")
  void asyncCancelDelayWrittenAndRemoved() throws Exception {
    try (final GenericContainer<?> container = prepared()) {
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithAsyncCancelDelay.class, containers(container), new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var plan = container.execInContainer("cat", PLAN_PATH);
      assertThat(plan.getStdout()).contains("ChaosAsyncCancelDelay");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after = container.execInContainer("cat", PLAN_PATH);
      assertThat(after.getStdout().trim()).isEqualTo(EMPTY_PLAN);
    }
  }

  @Test
  @DisplayName(
      "@ChaosAsyncCompleteSuppress writes scenario to plan.json and removeAll clears it")
  void asyncCompleteSuppressWrittenAndRemoved() throws Exception {
    try (final GenericContainer<?> container = prepared()) {
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithAsyncCompleteSuppress.class, containers(container), new ChaosApplicationReport());

      assertThat(applied).hasSize(1);

      final var plan = container.execInContainer("cat", PLAN_PATH);
      assertThat(plan.getStdout()).contains("ChaosAsyncCompleteSuppress");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after = container.execInContainer("cat", PLAN_PATH);
      assertThat(after.getStdout().trim()).isEqualTo(EMPTY_PLAN);
    }
  }

  @Test
  @DisplayName("Two L1 annotations produce a merged plan; removeAll restores empty plan")
  void twoAnnotationsMergedAndRemoved() throws Exception {
    try (final GenericContainer<?> container = prepared()) {
      final List<AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithTwoAnnotations.class, containers(container), new ChaosApplicationReport());

      assertThat(applied).hasSize(2);

      final var plan = container.execInContainer("cat", PLAN_PATH);
      assertThat(plan.getStdout())
          .contains("ChaosAsyncCancelDelay")
          .contains("ChaosAsyncCompleteSuppress");

      assertThat(L1AnnotationProcessor.removeAll(applied)).isTrue();

      final var after = container.execInContainer("cat", PLAN_PATH);
      assertThat(after.getStdout().trim()).isEqualTo(EMPTY_PLAN);
    }
  }

  // ==================== Helpers ====================

  private static GenericContainer<?> prepared() {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(JRE_IMAGE)).withCommand("sleep", "infinity");
    new JavaAgentTransport().prepare(c);
    c.start();
    return c;
  }

  private static List<ContainerHandle> containers(final GenericContainer<?> c) {
    return List.of(new ContainerHandle(c, "", Override.class));
  }
}
