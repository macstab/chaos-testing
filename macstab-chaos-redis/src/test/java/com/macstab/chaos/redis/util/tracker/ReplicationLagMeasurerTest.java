/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link ReplicationLagMeasurer}.
 *
 * <p>Note: The actual replication lag measurement functionality is tested via integration tests in
 * the Sentinel cluster integration tests, as this class creates its own RedisClient internally
 * from container.getHost()/getFirstMappedPort() which requires a real Redis instance.
 *
 * <p>These unit tests focus on what can be tested without Docker: constructor validation, utility
 * class structure, and method signatures.
 */
@DisplayName("ReplicationLagMeasurer")
class ReplicationLagMeasurerTest {

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null master container")
    void shouldThrowForNullMaster() {
      // ARRANGE
      @SuppressWarnings("unchecked")
      final GenericContainer<?> replica = mock(GenericContainer.class);

      // ACT / ASSERT
      assertThatThrownBy(
              () -> ReplicationLagMeasurer.measureReplicationLag(null, replica, Duration.ofSeconds(5)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("master");
    }

    @Test
    @DisplayName("Should throw NPE for null replica container")
    void shouldThrowForNullReplica() {
      // ARRANGE
      @SuppressWarnings("unchecked")
      final GenericContainer<?> master = mock(GenericContainer.class);

      // ACT / ASSERT
      assertThatThrownBy(
              () -> ReplicationLagMeasurer.measureReplicationLag(master, null, Duration.ofSeconds(5)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("replica");
    }

    @Test
    @DisplayName("Should throw NPE for null timeout")
    void shouldThrowForNullTimeout() {
      // ARRANGE
      @SuppressWarnings("unchecked")
      final GenericContainer<?> master = mock(GenericContainer.class);
      @SuppressWarnings("unchecked")
      final GenericContainer<?> replica = mock(GenericContainer.class);

      // ACT / ASSERT
      assertThatThrownBy(() -> ReplicationLagMeasurer.measureReplicationLag(master, replica, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("timeout");
    }
  }

  @Nested
  @DisplayName("Utility class")
  class UtilityClass {

    @Test
    @DisplayName("Should throw UnsupportedOperationException when constructor invoked")
    void shouldThrowWhenConstructorInvoked() throws Exception {
      // ARRANGE
      final Constructor<ReplicationLagMeasurer> constructor =
          ReplicationLagMeasurer.class.getDeclaredConstructor();
      constructor.setAccessible(true);

      // ACT / ASSERT
      assertThatThrownBy(() -> constructor.newInstance())
          .isInstanceOf(InvocationTargetException.class)
          .hasCauseInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("Default timeout overload")
  class DefaultTimeoutOverload {

    @Test
    @DisplayName("Should have measureReplicationLag with default timeout overload")
    void shouldHaveDefaultTimeoutOverload() throws Exception {
      // ARRANGE / ACT
      final var method =
          ReplicationLagMeasurer.class.getDeclaredMethod(
              "measureReplicationLag", GenericContainer.class, GenericContainer.class);

      // ASSERT
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(Duration.class);
    }

    @Test
    @DisplayName("Should have measureReplicationLag with custom timeout overload")
    void shouldHaveCustomTimeoutOverload() throws Exception {
      // ARRANGE / ACT
      final var method =
          ReplicationLagMeasurer.class.getDeclaredMethod(
              "measureReplicationLag",
              GenericContainer.class,
              GenericContainer.class,
              Duration.class);

      // ASSERT
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(Duration.class);
    }
  }
}
