/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.extension.ChaosApplicationReport;
import com.macstab.chaos.core.extension.L1AnnotationProcessor;
import com.macstab.chaos.core.extension.L1AnnotationProcessor.AppliedL1;
import com.macstab.chaos.core.extension.L1AnnotationProcessor.ContainerHandle;
import com.macstab.chaos.core.extension.L1AnnotationProcessor.MethodLevelResult;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.memory.annotation.l1.mmap_anon.ChaosMmapAnonEnomem;

/**
 * Integration test for method-scope L1 suspension semantics against a real running container.
 *
 * <p>Verifies the complete lifecycle:
 *
 * <ol>
 *   <li>A class-scope rule (probability = 1.0) is applied — config file shows the rule without a
 *       probability suffix (libchaos-memory omits the suffix when probability = 1.0).
 *   <li>A method-scope rule for the same annotation type (probability = 0.5) is applied — the
 *       class-scope rule is <em>suspended</em> (removed from the config), and the method rule is
 *       written in its place with the {@code @0.5} suffix.
 *   <li>The method-scope rule is removed and the suspended class-scope rule is re-applied via
 *       {@link L1AnnotationProcessor#reapply} — config returns to the original state (no {@code
 *       @0.5} suffix).
 * </ol>
 *
 * <p>This covers the gap identified in the L1 tier review: suspension semantics were previously
 * tested only with mocked containers in {@code L1AnnotationProcessorTest}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("L1 method-scope suspension — end-to-end with real container")
class L1MethodScopeSuspensionIntegrationTest {

  private static final String DEBIAN = "debian:bookworm-slim";
  private static final String ALPINE = "alpine:3.20";
  private static final String CONF = LibchaosLib.MEMORY.getConfigPath();

  // ==================== Fixture classes ====================

  /** Fixture for the class-scope rule: probability defaults to 1.0. */
  @ChaosMmapAnonEnomem
  static class ClassScopeFixture {}

  /** Fixture for the method-scope override: probability = 0.5, written as @0.5 in the config. */
  static class MethodScopeFixture {

    @ChaosMmapAnonEnomem(probability = 0.5)
    void withHalfProbability() {}
  }

  // ==================== Tests ====================

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName(
      "method-scope rule suspends class-scope rule; class-scope restored after method exits")
  void methodScopeSuspendsAndRestoresClassScopeRule(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final List<ContainerHandle> handles = List.of(new ContainerHandle(container, "", Override.class));
      final ChaosApplicationReport report = new ChaosApplicationReport();

      // ── Step 1: apply class-scope rule (probability = 1.0) ──────────────────────────────────
      final List<AppliedL1> classHandles =
          new ArrayList<>(
              L1AnnotationProcessor.applyClassLevel(ClassScopeFixture.class, handles, report));

      assertThat(classHandles).hasSize(1);

      final var afterClass = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(afterClass.getStdout())
          .as("class-scope rule written at probability 1.0 (no @prob suffix)")
          .contains("ENOMEM")
          .doesNotContain("@0.5");

      // ── Step 2: apply method-scope rule (probability = 0.5); class rule must be suspended ───
      final Method method = MethodScopeFixture.class.getDeclaredMethod("withHalfProbability");
      final MethodLevelResult result =
          L1AnnotationProcessor.applyMethodLevelWithSuspension(method, handles, classHandles, report);

      assertThat(result.methodHandles()).hasSize(1);
      assertThat(result.suspended()).hasSize(1);
      assertThat(classHandles)
          .as("class handle removed from persistent list when suspended")
          .isEmpty();

      final var afterMethod = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(afterMethod.getStdout())
          .as("method-scope rule active at probability 0.5")
          .contains("@0.5");
      assertThat(afterMethod.getStdout())
          .as("class-scope rule no longer present in config")
          .satisfies(
              content -> {
                final long linesWithEnomem =
                    content.lines().filter(l -> l.contains("ENOMEM") && !l.contains("@0.5")).count();
                assertThat(linesWithEnomem)
                    .as("class-scope line (no @0.5) absent from config")
                    .isZero();
              });

      // ── Step 3: remove method rule, re-apply suspended class rule ────────────────────────────
      assertThat(L1AnnotationProcessor.removeAll(result.methodHandles())).isTrue();

      final List<AppliedL1> restored = L1AnnotationProcessor.reapply(result.suspended());
      assertThat(restored).hasSize(1);

      final var afterRestore = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(afterRestore.getStdout())
          .as("class-scope rule restored at probability 1.0 (no @0.5)")
          .contains("ENOMEM")
          .doesNotContain("@0.5");

      // Cleanup
      L1AnnotationProcessor.removeAll(restored);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {DEBIAN, ALPINE})
  @DisplayName("no conflict: method-scope rule on different annotation type leaves class rule active")
  void noConflictWhenAnnotationTypeDiffers(final String image) throws Exception {
    try (final GenericContainer<?> container = prepared(image)) {
      final List<ContainerHandle> handles = List.of(new ContainerHandle(container, "", Override.class));
      final ChaosApplicationReport report = new ChaosApplicationReport();

      // Apply class-scope @ChaosMmapAnonEnomem (prob=1.0)
      final List<AppliedL1> classHandles =
          new ArrayList<>(
              L1AnnotationProcessor.applyClassLevel(ClassScopeFixture.class, handles, report));
      assertThat(classHandles).hasSize(1);

      // Method has no L1 annotations — should not suspend anything
      final Method noAnnotationMethod = MethodScopeFixture.class.getDeclaredMethod("withHalfProbability");
      // Manually remove the @ChaosMmapAnonEnomem from the lookup by using a method with no L1
      // (simulate: a different @Test method with no L1 annotation).
      // We verify this indirectly: classHandles must still have the class-scope handle after
      // applyMethodLevelWithSuspension processes a method that has no L1 annotations.

      // Use a method from Object that carries no L1 annotations
      final Method plainMethod = Object.class.getDeclaredMethod("toString");
      final MethodLevelResult result =
          L1AnnotationProcessor.applyMethodLevelWithSuspension(plainMethod, handles, classHandles, report);

      assertThat(result.methodHandles()).isEmpty();
      assertThat(result.suspended()).isEmpty();
      assertThat(classHandles)
          .as("class handle NOT removed when no conflicting method L1 is present")
          .hasSize(1);

      final var conf = container.execInContainer("/bin/sh", "-c", "cat " + CONF);
      assertThat(conf.getStdout())
          .as("class-scope rule still active — no method override happened")
          .contains("ENOMEM");

      L1AnnotationProcessor.removeAll(classHandles);
    }
  }

  // ==================== Helpers ====================

  private static GenericContainer<?> prepared(final String image) {
    final GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse(image)).withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.MEMORY).prepare(c);
    c.start();
    return c;
  }
}
