/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link ReplicationLagMeasurer}.
 *
 * <p>Full master→replica replication testing requires a Sentinel cluster (Docker Compose /
 * devcontainer). These tests cover the core execution path using a standalone container as both
 * "master" and "replica" — this verifies the measurement machinery without requiring a real
 * replication topology.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("ReplicationLagMeasurer — Integration")
class ReplicationLagMeasurerIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

  @Nested
  @DisplayName("measureReplicationLag(master, replica)")
  class MeasureReplicationLag {

    @Test
    @DisplayName("Should return a non-negative Duration when reading back a written key")
    void shouldReturnNonNegativeDuration() {
      // ARRANGE — use the same container as both master and replica (no actual replication lag)
      // This exercises the full code path: write key to "master", poll "replica" for it

      // ACT
      final Duration lag = ReplicationLagMeasurer.measureReplicationLag(redis, redis);

      // ASSERT
      assertThat(lag).isGreaterThanOrEqualTo(Duration.ZERO);
      assertThat(lag).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Should return Duration with custom timeout")
    void shouldReturnDurationWithCustomTimeout() {
      // ARRANGE / ACT
      final Duration lag =
          ReplicationLagMeasurer.measureReplicationLag(redis, redis, Duration.ofSeconds(10));

      // ASSERT
      assertThat(lag).isGreaterThanOrEqualTo(Duration.ZERO);
    }
  }

  @Nested
  @DisplayName("timeout behaviour")
  class TimeoutBehaviour {

    @Test
    @DisplayName(
        "Should throw IllegalStateException when timeout is very short and key cannot replicate")
    void shouldThrowOnTimeout() {
      // ARRANGE — use an extremely short timeout so the measurer cannot possibly succeed
      // We use Duration.ofNanos(1) to guarantee timeout before any SET+GET round-trip

      // ACT / ASSERT
      assertThatThrownBy(
              () -> ReplicationLagMeasurer.measureReplicationLag(redis, redis, Duration.ofNanos(1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Replication did not complete");
    }
  }
}
