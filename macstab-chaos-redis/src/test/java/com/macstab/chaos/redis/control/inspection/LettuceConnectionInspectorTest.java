/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.control.role.ContainerRole;
import com.macstab.chaos.redis.control.role.RoleResolver;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Unit tests for {@link LettuceConnectionInspector}.
 *
 * <p>All dependencies are mocked. Docker-touching integration paths are covered by Testcontainers
 * integration tests.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("LettuceConnectionInspector")
class LettuceConnectionInspectorTest {

  @Mock private RoleResolver roleResolver;
  @Mock private StatefulRedisConnection<String, String> connection;
  @Mock private RedisCommands<String, String> syncCommands;

  @SuppressWarnings("rawtypes")
  private GenericContainer container;

  @BeforeEach
  @SuppressWarnings("rawtypes")
  void setUp() {
    container = mock(GenericContainer.class);
  }

  // ─── Constructor ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {

    @Test
    @DisplayName("Throws NPE for null containers")
    void shouldThrowForNullContainers() {
      assertThatThrownBy(() -> new LettuceConnectionInspector(null, roleResolver))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("containers");
    }

    @Test
    @DisplayName("Throws NPE for null roleResolver")
    void shouldThrowForNullRoleResolver() {
      assertThatThrownBy(() -> new LettuceConnectionInspector(List.of(), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("roleResolver");
    }
  }

  // ─── inspect(connection) — Tier 1 ────────────────────────────────────────

  @Nested
  @DisplayName("inspect(connection) — Tier 1 auto-detect")
  class InspectAutoDetectTests {

    @Test
    @DisplayName("Throws NPE for null connection")
    void shouldThrowForNullConnection() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspect((StatefulRedisConnection<?, ?>) null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Throws ISE when connection is closed")
    void shouldThrowForClosedConnection() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);
      when(connection.isOpen()).thenReturn(false);

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspect(connection))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("Throws ISE when endpoint extraction fails (no parseable address in toString)")
    void shouldThrowWhenEndpointExtractionFails() {
      // ARRANGE — connection.toString() returns unparseable string, no Netty channel
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);
      when(connection.isOpen()).thenReturn(true);
      // connection.toString() will return something like "Mock for StatefulRedisConnection" which
      // has no valid host:port pattern → extractEndpoint returns empty →
      // buildExtractionFailureException

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspect(connection))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Throws ISE when no container matches the extracted endpoint")
    void shouldThrowWhenNoContainerMatchesEndpoint() {
      // ARRANGE — connection toString contains a valid-looking redis:// URI but no container
      // matches
      final StatefulRedisConnection<?, ?> conn =
          mock(
              StatefulRedisConnection.class,
              org.mockito.Mockito.withSettings().name("redis://localhost:19999"));
      when(conn.isOpen()).thenReturn(true);

      // Container is running but on a different port
      when(container.isRunning()).thenReturn(true);
      when(container.getHost()).thenReturn("localhost");
      when(container.getFirstMappedPort()).thenReturn(6379);

      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(container), roleResolver);

      // ACT & ASSERT — exercises buildNoMatchException (and its lambda iterating containers)
      assertThatThrownBy(() -> inspector.inspect(conn)).isInstanceOf(IllegalStateException.class);
    }
  }

  // ─── inspect(connection, containerHint) — Tier 2 ─────────────────────────

  @Nested
  @DisplayName("inspect(connection, containerHint) — Tier 2 explicit hint")
  class InspectWithHintTests {

    @Test
    @DisplayName("Throws NPE for null connection")
    void shouldThrowForNullConnection() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspect(null, container))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("connection");
    }

    @Test
    @DisplayName("Throws NPE for null containerHint")
    void shouldThrowForNullContainerHint() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);
      when(connection.isOpen()).thenReturn(true);

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspect(connection, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("containerHint");
    }

    @Test
    @DisplayName("Throws ISE when connection is closed")
    void shouldThrowForClosedConnection() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);
      when(connection.isOpen()).thenReturn(false);

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspect(connection, container))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("Throws ISE when container is not running")
    void shouldThrowForNonRunningContainer() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);
      when(connection.isOpen()).thenReturn(true);
      when(container.isRunning()).thenReturn(false);
      when(container.getContainerId()).thenReturn("stopped-container");

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspect(connection, container))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("Returns ConnectionInfo with role and health when all preconditions pass")
    void shouldReturnConnectionInfoOnSuccess() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(container), roleResolver);

      when(connection.isOpen()).thenReturn(true);
      when(container.isRunning()).thenReturn(true);
      when(container.getContainerId()).thenReturn("running-container");
      when(roleResolver.resolve(container)).thenReturn(ContainerRole.MASTER);
      when(connection.sync()).thenReturn(syncCommands);
      when(syncCommands.ping()).thenReturn("PONG");

      // ACT
      final ConnectionInfo result = inspector.inspect(connection, container);

      // ASSERT
      assertThat(result.role()).isEqualTo(ContainerRole.MASTER);
      assertThat(result.container()).isSameAs(container);
      assertThat(result.healthy()).isTrue();
    }

    @Test
    @DisplayName("Returns unhealthy ConnectionInfo when PING fails")
    void shouldReturnUnhealthyWhenPingFails() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(container), roleResolver);

      when(connection.isOpen()).thenReturn(true);
      when(container.isRunning()).thenReturn(true);
      when(container.getContainerId()).thenReturn("running-container");
      when(roleResolver.resolve(container)).thenReturn(ContainerRole.REPLICA_0);
      when(connection.sync()).thenReturn(syncCommands);
      when(syncCommands.ping()).thenThrow(new RuntimeException("connection refused"));

      // ACT
      final ConnectionInfo result = inspector.inspect(connection, container);

      // ASSERT
      assertThat(result.role()).isEqualTo(ContainerRole.REPLICA_0);
      assertThat(result.healthy()).isFalse();
    }

    @Test
    @DisplayName("Returns UNHEALTHY when PING returns wrong value")
    void shouldReturnUnhealthyWhenPingReturnsWrongValue() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(container), roleResolver);

      when(connection.isOpen()).thenReturn(true);
      when(container.isRunning()).thenReturn(true);
      when(container.getContainerId()).thenReturn("replica-container");
      when(roleResolver.resolve(container)).thenReturn(ContainerRole.REPLICA_0);
      when(connection.sync()).thenReturn(syncCommands);
      when(syncCommands.ping()).thenReturn("LOADING");

      // ACT
      final ConnectionInfo result = inspector.inspect(connection, container);

      // ASSERT — "LOADING" != "PONG" → unhealthy
      assertThat(result.healthy()).isFalse();
    }
  }

  // ─── inspectManual() — Tier 3 ─────────────────────────────────────────────

  @Nested
  @DisplayName("inspectManual(container, description) — Tier 3 manual")
  class InspectManualTests {

    @Test
    @DisplayName("Throws NPE for null container")
    void shouldThrowForNullContainer() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspectManual(null, "desc"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Throws NPE for null description")
    void shouldThrowForNullDescription() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspectManual(container, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("connectionDescription");
    }

    @Test
    @DisplayName("Throws ISE when container is not running")
    void shouldThrowForNonRunningContainer() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(), roleResolver);
      when(container.isRunning()).thenReturn(false);
      when(container.getContainerId()).thenReturn("stopped-container");

      // ACT & ASSERT
      assertThatThrownBy(() -> inspector.inspectManual(container, "manual-desc"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("Returns ConnectionInfo marked as healthy=true when container is running")
    void shouldReturnHealthyConnectionInfoForRunningContainer() {
      // ARRANGE
      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(container), roleResolver);

      when(container.isRunning()).thenReturn(true);
      when(container.getContainerId()).thenReturn("running-manual");
      when(roleResolver.resolve(container)).thenReturn(ContainerRole.SENTINEL_0);

      // ACT
      final ConnectionInfo result = inspector.inspectManual(container, "manual-sentinel");

      // ASSERT
      assertThat(result.role()).isEqualTo(ContainerRole.SENTINEL_0);
      assertThat(result.container()).isSameAs(container);
      assertThat(result.healthy()).isTrue();
      assertThat(result.connectionInfo()).contains("MANUAL");
    }
  }

  // ─── buildNoMatchException() lambda coverage ─────────────────────────────

  @Nested
  @DisplayName("buildNoMatchException() — lambda iterates running containers")
  class BuildNoMatchExceptionTests {

    @Test
    @DisplayName("Exception message lists running containers with their roles")
    @SuppressWarnings("unchecked")
    void shouldIncludeRunningContainerDetailsInMessage() {
      // ARRANGE — one running container, mocked to return host/port
      // Connection toString matches redis://localhost:19999 which doesn't match any container
      final StatefulRedisConnection<?, ?> conn =
          mock(
              StatefulRedisConnection.class,
              org.mockito.Mockito.withSettings().name("redis://localhost:19999"));
      when(conn.isOpen()).thenReturn(true);

      final GenericContainer<?> runningContainer = mock(GenericContainer.class);
      when(runningContainer.isRunning()).thenReturn(true);
      when(runningContainer.getHost()).thenReturn("localhost");
      when(runningContainer.getFirstMappedPort()).thenReturn(6379);
      when(roleResolver.resolve(runningContainer)).thenReturn(ContainerRole.MASTER);

      final LettuceConnectionInspector inspector =
          new LettuceConnectionInspector(List.of(runningContainer), roleResolver);

      // ACT & ASSERT — message contains container details (exercises the lambda)
      assertThatThrownBy(() -> inspector.inspect(conn))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("localhost")
          .hasMessageContaining("6379");
    }
  }
}
