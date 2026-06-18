/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.redis.util.RedisCommandTracker;

@Testcontainers
@DisplayName("RedisCommandTracker — Integration")
final class RedisCommandTrackerIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

  @Test
  @DisplayName("should capture GET commands")
  void shouldCaptureGetCommands() throws Exception {
    // Arrange
    final RedisCommandTracker tracker = new RedisCommandTracker(redis);
    tracker.start();

    // Act — execute commands via redis-cli inside container
    redis.execInContainer("redis-cli", "SET", "testkey", "testval");
    redis.execInContainer("redis-cli", "GET", "testkey");

    Thread.sleep(200);
    tracker.stop();

    // Assert
    assertThat(tracker.countCommand("GET")).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("should count SET commands")
  void shouldCountSetCommands() throws Exception {
    // Arrange
    final RedisCommandTracker tracker = new RedisCommandTracker(redis);
    tracker.start();

    // Act
    redis.execInContainer("redis-cli", "SET", "key1", "val1");
    redis.execInContainer("redis-cli", "SET", "key2", "val2");

    Thread.sleep(200);
    tracker.stop();

    // Assert
    assertThat(tracker.countCommand("SET")).isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("reset() should clear captured commands")
  void shouldReset() throws Exception {
    // Arrange
    final RedisCommandTracker tracker = new RedisCommandTracker(redis);
    tracker.start();
    redis.execInContainer("redis-cli", "GET", "anything");
    Thread.sleep(200);
    tracker.stop();

    // Act
    tracker.reset();

    // Assert
    assertThat(tracker.size()).isZero();
  }
}
