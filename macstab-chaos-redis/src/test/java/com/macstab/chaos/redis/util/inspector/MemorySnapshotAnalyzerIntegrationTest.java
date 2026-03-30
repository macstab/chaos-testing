/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.redis.util.inspector.MemorySnapshotAnalyzer;
import com.macstab.chaos.redis.util.inspector.model.MemorySnapshot;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/** Integration tests for {@link MemorySnapshotAnalyzer}. */
@Testcontainers
@DisplayName("MemorySnapshotAnalyzer — Integration")
class MemorySnapshotAnalyzerIntegrationTest {

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
  @DisplayName("forContainer() — Lettuce backend")
  class ForContainer {

    @Test
    @DisplayName("Should create analyzer without error")
    void shouldCreateAnalyzer() {
      // ARRANGE / ACT
      try (final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forContainer(redis)) {
        // ASSERT
        assertThat(analyzer).isNotNull();
      }
    }
  }

  @Nested
  @DisplayName("forContainerShell() — Shell backend")
  class ForContainerShell {

    @Test
    @DisplayName("Should create shell-backed analyzer")
    void shouldCreateShellAnalyzer() {
      // ARRANGE / ACT
      try (final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forContainerShell(redis)) {
        // ASSERT
        assertThat(analyzer).isNotNull();
      }
    }

    @Test
    @DisplayName("Shell backend should snapshot and return non-zero memory")
    void shouldSnapshotViaShell() {
      // ARRANGE
      try (final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forContainerShell(redis)) {
        // ACT
        analyzer.snapshot();
        final MemorySnapshot snapshot = analyzer.getSnapshot();

        // ASSERT
        assertThat(snapshot.usedMemoryBytes()).isGreaterThan(0);
      }
    }

    @Test
    @DisplayName("Shell backend should report positive delta after writes")
    void shouldReportPositiveDeltaViaShell() {
      // ARRANGE
      try (final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forContainerShell(redis)) {
        analyzer.snapshot();

        // ACT
        for (int i = 0; i < 200; i++) {
          commands.set("shell:mem:key_" + i, "shell:mem:value_" + i);
        }
        final long delta = analyzer.getMemoryDelta();

        // ASSERT
        assertThat(delta).isGreaterThan(0);
      }
    }
  }

  @Nested
  @DisplayName("positive delta after writes")
  class PositiveDeltaAfterWrites {

    @Test
    @DisplayName("Should show positive delta after writing keys")
    void shouldShowPositiveDelta() {
      // ARRANGE
      try (final MemorySnapshotAnalyzer analyzer =
          MemorySnapshotAnalyzer.forCommands(commands)) {
        analyzer.snapshot();

        // ACT
        for (int i = 0; i < 500; i++) {
          commands.set("key_" + i, "value" + i);
        }
        final long delta = analyzer.getMemoryDelta();

        // ASSERT
        assertThat(delta).isGreaterThan(0);
      }
    }
  }

  @Nested
  @DisplayName("assertNoMemoryLeak with large tolerance")
  class AssertNoMemoryLeakWithLargeTolerance {

    @Test
    @DisplayName("Should pass with large tolerance after write and delete")
    void shouldPassWithLargeTolerance() {
      // ARRANGE
      try (final MemorySnapshotAnalyzer analyzer =
          MemorySnapshotAnalyzer.forCommands(commands)) {
        analyzer.snapshot();

        // ACT
        for (int i = 0; i < 100; i++) {
          commands.set("temp:key_" + i, "temp:value" + i);
        }
        for (int i = 0; i < 100; i++) {
          commands.del("temp:key_" + i);
        }

        // ASSERT
        analyzer.assertNoMemoryLeak(5 * 1024 * 1024);
      }
    }
  }

  @Nested
  @DisplayName("getCurrent() returns non-zero memory")
  class GetCurrentReturnsNonZero {

    @Test
    @DisplayName("Should return non-zero used memory")
    void shouldReturnNonZeroMemory() {
      // ARRANGE
      try (final MemorySnapshotAnalyzer analyzer =
          MemorySnapshotAnalyzer.forCommands(commands)) {
        // ACT
        final MemorySnapshot snapshot = analyzer.getCurrent();

        // ASSERT
        assertThat(snapshot.usedMemoryBytes()).isGreaterThan(0);
      }
    }
  }
}
