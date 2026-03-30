/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/** Integration tests for {@link ConnectionLeakTracker}. */
@Testcontainers
@DisplayName("ConnectionLeakTracker — Integration")
class ConnectionLeakTrackerIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

  private static RedisClient redisClient;
  private static StatefulRedisConnection<String, String> connection;
  private static RedisCommands<String, String> commands;

  @BeforeAll
  static void setUp() {
    redisClient =
        RedisClient.create(
            RedisURI.builder()
                .withHost(redis.getHost())
                .withPort(redis.getFirstMappedPort())
                .build());
    connection = redisClient.connect();
    commands = connection.sync();
  }

  @AfterAll
  static void tearDown() {
    if (connection != null) {
      connection.close();
    }
    if (redisClient != null) {
      redisClient.shutdown();
    }
  }

  @Nested
  @DisplayName("forContainer()")
  class ForContainer {

    @Test
    @DisplayName("Should create tracker without error")
    void shouldCreateTracker() {
      // ARRANGE / ACT
      try (final ConnectionLeakTracker tracker = ConnectionLeakTracker.forContainer(redis)) {
        // ASSERT
        assertThat(tracker).isNotNull();
        assertThat(tracker.hasSnapshot()).isFalse();
      }
    }
  }

  @Nested
  @DisplayName("snapshot and no leak")
  class SnapshotAndNoLeak {

    @Test
    @DisplayName("Should pass assertNoLeaks after commands")
    void shouldPassAfterCommands() {
      // ARRANGE
      try (final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(commands)) {
        tracker.snapshot();

        // ACT
        commands.ping();
        commands.set("test:key", "value");
        commands.get("test:key");

        // ASSERT
        tracker.assertNoLeaks();
      }
    }
  }

  @Nested
  @DisplayName("detects leaked connection")
  class DetectsLeakedConnection {

    @Test
    @DisplayName("Should detect new connection as leak")
    void shouldDetectLeak() {
      // ARRANGE
      try (final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(commands)) {
        tracker.snapshot();

        final RedisClient leakedClient =
            RedisClient.create(
                RedisURI.builder()
                    .withHost(redis.getHost())
                    .withPort(redis.getFirstMappedPort())
                    .build());
        final StatefulRedisConnection<String, String> leakedConnection = leakedClient.connect();

        // ACT
        final var newConnections = tracker.getNewConnections();

        // ASSERT
        assertThat(newConnections).hasSizeGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> tracker.assertNoLeaks()).isInstanceOf(AssertionError.class);

        // CLEANUP
        leakedConnection.close();
        leakedClient.shutdown();
      }
    }
  }

  @Nested
  @DisplayName("detects no leak after close")
  class DetectsNoLeakAfterClose {

    @Test
    @DisplayName("Should not detect leak when connection closed before check")
    void shouldNotDetectLeakAfterClose() {
      // ARRANGE
      try (final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(commands)) {
        tracker.snapshot();

        final RedisClient temporaryClient =
            RedisClient.create(
                RedisURI.builder()
                    .withHost(redis.getHost())
                    .withPort(redis.getFirstMappedPort())
                    .build());
        final StatefulRedisConnection<String, String> temporaryConnection =
            temporaryClient.connect();

        // ACT
        temporaryConnection.close();
        temporaryClient.shutdown();
        Thread.sleep(100);

        // ASSERT
        tracker.assertNoLeaks();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
  }
}
