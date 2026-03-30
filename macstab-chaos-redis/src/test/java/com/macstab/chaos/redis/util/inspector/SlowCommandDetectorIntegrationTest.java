/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

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

/** Integration tests for {@link SlowCommandDetector}. */
@Testcontainers
@DisplayName("SlowCommandDetector — Integration")
class SlowCommandDetectorIntegrationTest {

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
    @DisplayName("Should create detector without error")
    void shouldCreateDetector() {
      // ARRANGE / ACT
      try (final SlowCommandDetector detector = SlowCommandDetector.forContainer(redis)) {
        // ASSERT
        assertThat(detector).isNotNull();
      }
    }
  }

  @Nested
  @DisplayName("reset()")
  class Reset {

    @Test
    @DisplayName("Should reset slowlog and return this for chaining")
    void shouldResetAndReturnThis() {
      // ARRANGE
      try (final SlowCommandDetector detector = SlowCommandDetector.forCommands(commands)) {
        commands.ping();

        // ACT
        final SlowCommandDetector result = detector.reset();

        // ASSERT
        assertThat(result).isSameAs(detector);
        assertThat(detector.getSlowCommands()).isEmpty();
      }
    }
  }

  @Nested
  @DisplayName("slowlog capture")
  class SlowlogCapture {

    @Test
    @DisplayName("Should capture slow commands after configuration")
    void shouldCaptureSlowCommands() {
      // ARRANGE
      try (final SlowCommandDetector detector = SlowCommandDetector.forCommands(commands)) {
        commands.configSet("slowlog-log-slower-than", "0");
        detector.reset();

        // ACT
        commands.ping();
        commands.set("test:key", "test:value");
        commands.get("test:key");

        // ASSERT
        final var slowCommands = detector.getSlowCommands();
        assertThat(slowCommands).isNotEmpty();
      }
    }

    @Test
    @DisplayName("Should show empty slowlog after reset")
    void shouldShowEmptyAfterReset() {
      // ARRANGE
      try (final SlowCommandDetector detector = SlowCommandDetector.forCommands(commands)) {
        commands.configSet("slowlog-log-slower-than", "0");
        commands.ping();
        detector.reset();

        // ACT
        commands.ping();
        detector.reset();

        // ASSERT
        assertThat(detector.getSlowCommands()).isEmpty();
      }
    }
  }

  @Nested
  @DisplayName("assertNoSlowCommands(large threshold)")
  class AssertNoSlowCommandsLargeThreshold {

    @Test
    @DisplayName("Should pass with large threshold")
    void shouldPassWithLargeThreshold() {
      // ARRANGE
      try (final SlowCommandDetector detector = SlowCommandDetector.forCommands(commands)) {
        commands.configSet("slowlog-log-slower-than", "0");
        detector.reset();
        commands.ping();
        commands.set("test:key", "value");

        // ACT / ASSERT
        detector.assertNoSlowCommands(Duration.ofSeconds(10));
      }
    }
  }

  @Nested
  @DisplayName("assertNoSlowCommands(zero threshold)")
  class AssertNoSlowCommandsZeroThreshold {

    @Test
    @DisplayName("Should throw AssertionError with zero threshold")
    void shouldThrowWithZeroThreshold() {
      // ARRANGE
      try (final SlowCommandDetector detector = SlowCommandDetector.forCommands(commands)) {
        commands.configSet("slowlog-log-slower-than", "0");
        detector.reset();
        commands.ping();

        // ACT / ASSERT
        assertThatThrownBy(() -> detector.assertNoSlowCommands(Duration.ZERO))
            .isInstanceOf(AssertionError.class);
      }
    }
  }
}
