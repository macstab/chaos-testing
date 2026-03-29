/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.control.role.ContainerRole;

/**
 * Unit tests for {@link RoleDetector}.
 *
 * <p>{@link #detect} paths that call {@link com.macstab.chaos.core.util.Shell#exec} require a
 * running container and are covered by integration tests. All Docker-free paths are covered here.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleDetector")
class RoleDetectorTest {

  // ─── Constructor guard ────────────────────────────────────────────────────

  @Nested
  @DisplayName("Constructor guard")
  class ConstructorGuardTests {

    @Test
    @DisplayName("Constructor throws UnsupportedOperationException via reflection")
    void shouldThrowOnReflectiveInstantiation() throws Exception {
      // ARRANGE
      final Constructor<RoleDetector> ctor = RoleDetector.class.getDeclaredConstructor();
      ctor.setAccessible(true);

      // ACT & ASSERT
      assertThatThrownBy(ctor::newInstance)
          .isInstanceOf(InvocationTargetException.class)
          .hasCauseInstanceOf(UnsupportedOperationException.class);
    }
  }

  // ─── parseRoleOutput() ────────────────────────────────────────────────────

  @Nested
  @DisplayName("parseRoleOutput()")
  class ParseRoleOutputTests {

    @Test
    @DisplayName("null output → UNKNOWN")
    void nullOutput() {
      assertThat(RoleDetector.parseRoleOutput(null)).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("blank output → UNKNOWN")
    void blankOutput() {
      assertThat(RoleDetector.parseRoleOutput("   ")).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("empty string → UNKNOWN")
    void emptyOutput() {
      assertThat(RoleDetector.parseRoleOutput("")).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("first line contains 'master' → MASTER")
    void masterOutput() {
      // ARRANGE — real redis-cli ROLE output starts with "master"
      final String output = "master\n1234\nreplica1 6379 1234\n";

      // ACT & ASSERT
      assertThat(RoleDetector.parseRoleOutput(output)).isEqualTo(ContainerRole.MASTER);
    }

    @Test
    @DisplayName("first line contains 'slave' → REPLICA_0")
    void slaveOutput() {
      final String output = "slave\nmaster 172.18.0.2 6379\n0\n";
      assertThat(RoleDetector.parseRoleOutput(output)).isEqualTo(ContainerRole.REPLICA_0);
    }

    @Test
    @DisplayName("first line contains 'replica' → REPLICA_0")
    void replicaOutput() {
      final String output = "replica\nmaster 172.18.0.2 6379\n0\n";
      assertThat(RoleDetector.parseRoleOutput(output)).isEqualTo(ContainerRole.REPLICA_0);
    }

    @Test
    @DisplayName("first line contains 'sentinel' → SENTINEL_0")
    void sentinelOutput() {
      final String output = "sentinel\nmymaster\n";
      assertThat(RoleDetector.parseRoleOutput(output)).isEqualTo(ContainerRole.SENTINEL_0);
    }

    @Test
    @DisplayName("unrecognized first line → UNKNOWN")
    void unrecognizedOutput() {
      final String output = "unknown_role\nsome extra data\n";
      assertThat(RoleDetector.parseRoleOutput(output)).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("multi-line output: only first line matters")
    void onlyFirstLineMatters() {
      // ARRANGE — first line is blank/empty, second contains "master" → still UNKNOWN
      final String output = "\nmaster\n1234\n";
      assertThat(RoleDetector.parseRoleOutput(output)).isEqualTo(ContainerRole.UNKNOWN);
    }
  }

  // ─── detect() — Docker-free paths ────────────────────────────────────────

  @Nested
  @DisplayName("detect() — Docker-free paths")
  class DetectTests {

    @Test
    @DisplayName("Throws NPE for null container")
    void shouldThrowForNullContainer() {
      assertThatThrownBy(() -> RoleDetector.detect(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Returns UNKNOWN for non-running container")
    @SuppressWarnings("rawtypes")
    void shouldReturnUnknownForStoppedContainer() {
      // ARRANGE
      final GenericContainer container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);

      // ACT & ASSERT
      assertThat(RoleDetector.detect(container)).isEqualTo(ContainerRole.UNKNOWN);
    }
  }

  // ─── detectAll() ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("detectAll()")
  class DetectAllTests {

    @Test
    @DisplayName("Throws NPE for null list")
    void shouldThrowForNullList() {
      assertThatThrownBy(() -> RoleDetector.detectAll(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Returns empty map for empty list")
    void shouldReturnEmptyMapForEmptyList() {
      assertThat(RoleDetector.detectAll(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Detects UNKNOWN for each non-running container in the list")
    @SuppressWarnings("rawtypes")
    void shouldDetectRoleForEachContainer() {
      // ARRANGE
      final GenericContainer c1 = mock(GenericContainer.class);
      final GenericContainer c2 = mock(GenericContainer.class);
      when(c1.isRunning()).thenReturn(false);
      when(c2.isRunning()).thenReturn(false);

      // ACT
      final var result = RoleDetector.detectAll(List.of(c1, c2));

      // ASSERT
      assertThat(result).hasSize(2);
      assertThat(result.get(c1)).isEqualTo(ContainerRole.UNKNOWN);
      assertThat(result.get(c2)).isEqualTo(ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("Preserves input order in result map")
    @SuppressWarnings("rawtypes")
    void shouldPreserveInputOrder() {
      // ARRANGE
      final GenericContainer c1 = mock(GenericContainer.class);
      final GenericContainer c2 = mock(GenericContainer.class);
      final GenericContainer c3 = mock(GenericContainer.class);
      when(c1.isRunning()).thenReturn(false);
      when(c2.isRunning()).thenReturn(false);
      when(c3.isRunning()).thenReturn(false);

      // ACT
      final var result = RoleDetector.detectAll(List.of(c1, c2, c3));

      // ASSERT
      assertThat(result.keySet()).containsExactly(c1, c2, c3);
    }
  }
}
