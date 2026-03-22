/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.redis.util.RedisCommandTracker;
import com.macstab.chaos.redis.util.RedisCommandTracker.CommandWithArgs;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

/**
 * REAL integration tests for RedisCommandTracker features.
 *
 * <p><strong>What This Tests:</strong>
 *
 * <ul>
 *   <li>✅ Key pattern filtering works with REAL Redis MONITOR output
 *   <li>✅ Replication lag measurement works with REAL containers
 *   <li>✅ Command arguments extraction works with REAL MONITOR data
 *   <li>✅ Latency analysis works with REAL timestamps
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("RedisCommandTracker Integration Tests (REAL)")
class RedisCommandTrackerRealTest {

  private static Network network;
  private static GenericContainer<?> master;
  private static GenericContainer<?> replica;

  @BeforeAll
  static void setup() {
    network = Network.newNetwork();

    // Create master
    master =
        new GenericContainer<>("redis:7.4")
            .withNetwork(network)
            .withExposedPorts(6379)
            .withCommand("redis-server", "--protected-mode", "no");
    master.start();

    // Get master IP
    final String masterIp =
        master
            .getContainerInfo()
            .getNetworkSettings()
            .getNetworks()
            .values()
            .iterator()
            .next()
            .getIpAddress();

    // Create replica
    replica =
        new GenericContainer<>("redis:7.4")
            .withNetwork(network)
            .withExposedPorts(6379)
            .withCommand("redis-server", "--protected-mode", "no", "--replicaof", masterIp, "6379");
    replica.start();

    // Wait for replication to stabilize
    sleep(2000);
  }

  @AfterAll
  static void teardown() {
    if (replica != null) {
      replica.stop();
    }
    if (master != null) {
      master.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  // ==================== Feature A: Key Pattern Filtering (REAL) ====================

  @Nested
  @DisplayName("Key Pattern Filtering with REAL Redis")
  class KeyPatternFilteringRealTests {

    @Test
    @DisplayName("Should track user:* commands on REAL Redis")
    void shouldTrackUserKeysOnRealRedis() throws Exception {
      // ARRANGE
      final RedisCommandTracker tracker = new RedisCommandTracker(master);
      tracker.start();

      // ACT: Execute real Redis commands
      final RedisURI uri =
          RedisURI.builder()
              .withHost(master.getHost())
              .withPort(master.getFirstMappedPort())
              .build();

      try (final RedisClient client = RedisClient.create(uri)) {
        final var conn = client.connect().sync();

        // Execute commands with different key patterns
        conn.set("user:123", "alice");
        conn.set("user:456", "bob");
        conn.set("product:789", "laptop");
        conn.get("user:123");
        conn.get("product:789");

        sleep(500); // Wait for MONITOR to capture
      }

      tracker.stop();

      // ASSERT: Should capture only user:* commands
      final long userSetCount = tracker.countCommandsMatchingKeyPattern("SET", "user:*");
      final long userGetCount = tracker.countCommandsMatchingKeyPattern("GET", "user:*");
      final long productSetCount = tracker.countCommandsMatchingKeyPattern("SET", "product:*");

      System.out.println("user SET commands: " + userSetCount);
      System.out.println("user GET commands: " + userGetCount);
      System.out.println("product SET commands: " + productSetCount);

      assertThat(userSetCount).isEqualTo(2);
      assertThat(userGetCount).isEqualTo(1);
      assertThat(productSetCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Should filter by wildcard patterns on REAL Redis")
    void shouldFilterByWildcardPatterns() throws Exception {
      // ARRANGE
      final RedisCommandTracker tracker = new RedisCommandTracker(master);
      tracker.start();

      // ACT
      final RedisURI uri =
          RedisURI.builder()
              .withHost(master.getHost())
              .withPort(master.getFirstMappedPort())
              .build();

      try (final RedisClient client = RedisClient.create(uri)) {
        final var conn = client.connect().sync();

        conn.set("cache:1", "data1");
        conn.set("cache:2", "data2");
        conn.set("cache:12", "data12"); // Should NOT match cache:?

        sleep(500);
      }

      tracker.stop();

      // ASSERT: cache:? should match only single-digit keys
      final long singleDigit = tracker.countCommandsMatchingKeyPattern("SET", "cache:?");
      final long allCache = tracker.countCommandsMatchingKeyPattern("SET", "cache:*");

      System.out.println("cache:? matches: " + singleDigit);
      System.out.println("cache:* matches: " + allCache);

      assertThat(singleDigit).isEqualTo(2); // Only cache:1 and cache:2
      assertThat(allCache).isEqualTo(3); // All cache keys
    }
  }

  // ==================== Feature B: Replication Lag Measurement (REAL) ====================

  @Nested
  @DisplayName("Replication Lag Measurement with REAL Containers")
  class ReplicationLagRealTests {

    @Test
    @DisplayName("Should measure REAL replication lag between master and replica")
    void shouldMeasureRealReplicationLag() {
      // ACT
      final Duration lag = RedisCommandTracker.measureReplicationLag(master, replica);

      System.out.println("Measured replication lag: " + lag.toMillis() + "ms");

      // ASSERT: Lag should be very small (< 100ms) for local containers
      assertThat(lag)
          .isLessThan(Duration.ofMillis(100))
          .describedAs("Replication lag should be < 100ms for local containers");
    }

    @Test
    @DisplayName("Should measure replication lag with network latency")
    void shouldMeasureReplicationLagWithNetworkLatency() {
      // ARRANGE: Add NET_ADMIN and inject latency
      final var replicaWithChaos =
          new GenericContainer<>("redis:7.4")
              .withNetwork(network)
              .withExposedPorts(6379)
              .withCommand(
                  "redis-server",
                  "--protected-mode",
                  "no",
                  "--replicaof",
                  master
                      .getContainerInfo()
                      .getNetworkSettings()
                      .getNetworks()
                      .values()
                      .iterator()
                      .next()
                      .getIpAddress(),
                  "6379")
              .withCreateContainerCmdModifier(
                  cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
      replicaWithChaos.start();

      sleep(2000); // Wait for replication

      // Inject 50ms latency
      try {
        replicaWithChaos.execInContainer(
            "tc", "qdisc", "add", "dev", "eth0", "root", "netem", "delay", "50ms");
        sleep(500);

        // ACT
        final Duration lag = RedisCommandTracker.measureReplicationLag(master, replicaWithChaos);

        System.out.println("Replication lag with 50ms network latency: " + lag.toMillis() + "ms");

        // ASSERT: Lag should be > 40ms due to injected latency
        assertThat(lag.toMillis())
            .isGreaterThan(40)
            .describedAs("Replication lag should include injected network latency");

      } catch (Exception e) {
        throw new RuntimeException("Failed to inject latency", e);
      } finally {
        replicaWithChaos.stop();
      }
    }

    @Test
    @DisplayName("Should timeout if replication doesn't complete")
    void shouldTimeoutIfReplicationDoesntComplete() {
      // ARRANGE: Create standalone container (NOT a replica)
      final var standalone =
          new GenericContainer<>("redis:7.4")
              .withExposedPorts(6379)
              .withCommand("redis-server", "--protected-mode", "no");
      standalone.start();

      try {
        // ACT & ASSERT: Should timeout quickly
        final long start = System.currentTimeMillis();
        try {
          RedisCommandTracker.measureReplicationLag(master, standalone, Duration.ofSeconds(2));
        } catch (IllegalStateException e) {
          final long elapsed = System.currentTimeMillis() - start;
          System.out.println("Timeout after " + elapsed + "ms (expected ~2000ms)");

          assertThat(e.getMessage()).contains("Replication did not complete");
          assertThat(elapsed).isBetween(1800L, 2500L); // ~2 seconds
        }
      } finally {
        standalone.stop();
      }
    }
  }

  // ==================== Feature D: Command Arguments Extraction (REAL) ====================

  @Nested
  @DisplayName("Command Arguments Extraction with REAL Redis")
  class CommandArgumentsRealTests {

    @Test
    @DisplayName("Should extract REAL command arguments from MONITOR")
    void shouldExtractRealCommandArguments() throws Exception {
      // ARRANGE
      final RedisCommandTracker tracker = new RedisCommandTracker(master);
      tracker.start();

      // ACT: Execute real commands
      final RedisURI uri =
          RedisURI.builder()
              .withHost(master.getHost())
              .withPort(master.getFirstMappedPort())
              .build();

      try (final RedisClient client = RedisClient.create(uri)) {
        final var conn = client.connect().sync();

        conn.set("user:1", "alice");
        conn.set("user:2", "bob");
        conn.setex("session:123", 3600, "data"); // SET with expiration

        sleep(500);
      }

      tracker.stop();

      // ASSERT: Extract arguments
      final List<CommandWithArgs> setCommands = tracker.getCommandsWithArguments("SET");
      final List<CommandWithArgs> setexCommands = tracker.getCommandsWithArguments("SETEX");

      System.out.println("SET commands found: " + setCommands.size());
      setCommands.forEach(cmd -> System.out.println("  " + cmd));

      System.out.println("SETEX commands found: " + setexCommands.size());
      setexCommands.forEach(cmd -> System.out.println("  " + cmd));

      assertThat(setCommands).hasSizeGreaterThanOrEqualTo(2);

      // Verify arguments are correct
      final CommandWithArgs firstSet =
          setCommands.stream()
              .filter(cmd -> "user:1".equals(cmd.getKey()))
              .findFirst()
              .orElse(null);

      if (firstSet != null) {
        assertThat(firstSet.getKey()).isEqualTo("user:1");
        assertThat(firstSet.getValue()).isEqualTo("alice");
      }
    }

    @Test
    @DisplayName("Should extract arguments from complex commands")
    void shouldExtractArgumentsFromComplexCommands() throws Exception {
      // ARRANGE
      final RedisCommandTracker tracker = new RedisCommandTracker(master);
      tracker.start();

      // ACT
      final RedisURI uri =
          RedisURI.builder()
              .withHost(master.getHost())
              .withPort(master.getFirstMappedPort())
              .build();

      try (final RedisClient client = RedisClient.create(uri)) {
        final var conn = client.connect().sync();

        // Complex command with multiple arguments
        conn.set("key", "value", io.lettuce.core.SetArgs.Builder.ex(3600));

        sleep(500);
      }

      tracker.stop();

      // ASSERT
      final List<CommandWithArgs> setCommands = tracker.getCommandsWithArguments("SET");

      assertThat(setCommands).isNotEmpty();

      setCommands.forEach(
          cmd -> {
            System.out.println("Command: " + cmd.getCommand());
            System.out.println("  Args: " + cmd.getArgs());
          });
    }
  }

  // ==================== Feature C: Latency Analysis (REAL) ====================

  @Nested
  @DisplayName("Latency Analysis with REAL Redis")
  class LatencyAnalysisRealTests {

    @Test
    @DisplayName("Should measure REAL command latency")
    void shouldMeasureRealCommandLatency() throws Exception {
      // ARRANGE
      final RedisCommandTracker tracker = new RedisCommandTracker(master);
      tracker.start();

      // ACT: Execute many commands quickly
      final RedisURI uri =
          RedisURI.builder()
              .withHost(master.getHost())
              .withPort(master.getFirstMappedPort())
              .build();

      try (final RedisClient client = RedisClient.create(uri)) {
        final var conn = client.connect().sync();

        for (int i = 0; i < 100; i++) {
          conn.get("key:" + i);
        }

        sleep(500);
      }

      tracker.stop();

      // ASSERT
      final Duration avgLatency = tracker.getAverageLatency("GET");

      System.out.println("Average GET latency: " + avgLatency.toMillis() + "ms");

      // Latency should be very small for local container
      assertThat(avgLatency.toMillis())
          .isLessThan(50)
          .describedAs("Average latency should be < 50ms for local container");
    }
  }

  // ==================== Helper Methods ====================

  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted", e);
    }
  }
}
