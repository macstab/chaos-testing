/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/** Comprehensive unit tests for {@link ShellRedisCommandExecutor}. */
@DisplayName("ShellRedisCommandExecutor")
class ShellRedisCommandExecutorTest {

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null container")
    void shouldThrowForNullContainer() {
      // ARRANGE / ACT / ASSERT
      assertThatThrownBy(() -> new ShellRedisCommandExecutor(null, 6379))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should throw IAE for port 0")
    void shouldThrowForPortZero() {
      // ARRANGE
      @SuppressWarnings("unchecked")
      final GenericContainer<?> container = mock(GenericContainer.class);

      // ACT / ASSERT
      assertThatThrownBy(() -> new ShellRedisCommandExecutor(container, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("1-65535");
    }

    @Test
    @DisplayName("Should throw IAE for port 65536")
    void shouldThrowForPortAboveRange() {
      // ARRANGE
      @SuppressWarnings("unchecked")
      final GenericContainer<?> container = mock(GenericContainer.class);

      // ACT / ASSERT
      assertThatThrownBy(() -> new ShellRedisCommandExecutor(container, 65536))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("1-65535");
    }

    @Test
    @DisplayName("Should accept valid port 6379")
    void shouldAcceptValidPort() {
      // ARRANGE
      @SuppressWarnings("unchecked")
      final GenericContainer<?> container = mock(GenericContainer.class);

      // ACT
      final ShellRedisCommandExecutor executor = new ShellRedisCommandExecutor(container, 6379);

      // ASSERT
      assertThat(executor).isNotNull();
    }
  }

  @Nested
  @DisplayName("getPort()")
  class GetPort {

    @Test
    @DisplayName("Should return configured port")
    void shouldReturnConfiguredPort() {
      // ARRANGE
      @SuppressWarnings("unchecked")
      final GenericContainer<?> container = mock(GenericContainer.class);
      final ShellRedisCommandExecutor executor = new ShellRedisCommandExecutor(container, 6380);

      // ACT
      final int port = executor.getPort();

      // ASSERT
      assertThat(port).isEqualTo(6380);
    }
  }

  @Nested
  @DisplayName("Constants")
  class Constants {

    @Test
    @DisplayName("Should have DEFAULT_REDIS_PORT as 6379")
    void shouldHaveDefaultRedisPort() {
      // ARRANGE / ACT / ASSERT
      assertThat(ShellRedisCommandExecutor.DEFAULT_REDIS_PORT).isEqualTo(6379);
    }
  }

  @Nested
  @DisplayName("execute() — null handling")
  class ExecuteNullHandling {

    @Test
    @DisplayName("Should throw NPE for null command")
    void shouldThrowForNullCommand() {
      // ARRANGE
      @SuppressWarnings("unchecked")
      final GenericContainer<?> container = mock(GenericContainer.class);
      final ShellRedisCommandExecutor executor = new ShellRedisCommandExecutor(container, 6379);

      // ACT / ASSERT
      assertThatThrownBy(() -> executor.execute(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("redisCommand");
    }
  }
}
