/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.network.condition.DisabledOnNonLinuxHost;
import com.macstab.chaos.redis.control.ControlFacade;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

/**
 * REAL integration tests for network chaos engineering.
 *
 * <p><strong>Requirements:</strong> Linux host or dev container with NET_ADMIN capability.
 *
 * <p><strong>What This Tests:</strong>
 *
 * <ul>
 *   <li>✅ Latency injection ACTUALLY adds latency (measured with PING)
 *   <li>✅ Packet loss ACTUALLY drops packets (measured with multiple PINGs)
 *   <li>✅ Jitter ACTUALLY varies latency (measured with variance)
 *   <li>✅ Network reset ACTUALLY restores normal behavior
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisabledOnNonLinuxHost("Network chaos requires Linux host or dev container")
@DisplayName("Network Chaos Integration Tests (REAL)")
class NetworkChaosRealTest {

  private static Network network;
  private static GenericContainer<?> master;
  private static GenericContainer<?> replica;
  private static ControlFacade control;

  @BeforeAll
  static void setup() {
    network = Network.newNetwork();

    // Create master with NET_ADMIN capability
    master =
        new GenericContainer<>("redis:7.4")
            .withNetwork(network)
            .withExposedPorts(6379)
            .withCommand("redis-server", "--protected-mode", "no")
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    master.start();

    // Install network tools (tc, iptables) in master
    installNetworkTools(master);

    // Get master IP for replica configuration
    final String masterIp =
        master
            .getContainerInfo()
            .getNetworkSettings()
            .getNetworks()
            .values()
            .iterator()
            .next()
            .getIpAddress();

    // Create replica with NET_ADMIN capability
    replica =
        new GenericContainer<>("redis:7.4")
            .withNetwork(network)
            .withExposedPorts(6379)
            .withCommand("redis-server", "--protected-mode", "no", "--replicaof", masterIp, "6379")
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    replica.start();

    // Install network tools (tc, iptables) in replica
    installNetworkTools(replica);

    // Wait for replication to stabilize
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Create ControlFacade
    final var allContainers = List.of(master, replica);
    final var containerIndexMap = java.util.Map.of(master, 0, replica, 1);
    control = ControlFacade.create(allContainers, containerIndexMap);
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

  @AfterEach
  void cleanup() {
    // Reset all network chaos after each test
    control.network().resetAll();
  }

  // ==================== Latency Injection Tests ====================

  @Nested
  @DisplayName("Latency Injection (REAL)")
  class LatencyInjectionTests {

    @Test
    @DisplayName("Should ACTUALLY inject 100ms latency (measured with PING)")
    void shouldActuallyInjectLatency() {
      // ARRANGE: Get baseline ping time
      final long baselinePing = measurePingTime(replica);
      System.out.println("Baseline ping: " + baselinePing + "ms");

      // ACT: Inject 100ms latency
      control.network().injectLatency(replica, Duration.ofMillis(100));

      // Wait for tc rules to apply
      sleep(500);

      // ASSERT: Ping time should increase by ~100ms
      final long slowPing = measurePingTime(replica);
      final long increase = slowPing - baselinePing;

      System.out.println(
          "After latency injection: " + slowPing + "ms (increase: " + increase + "ms)");

      // Allow variance (50-150ms increase is acceptable due to network jitter)
      assertThat(increase)
          .isGreaterThan(50L)
          .describedAs("Latency should increase by at least 50ms");
    }

    @Test
    @DisplayName("Should reset latency injection")
    void shouldResetLatencyInjection() {
      // ARRANGE: Inject latency
      control.network().injectLatency(replica, Duration.ofMillis(100));
      sleep(500);
      final long slowPing = measurePingTime(replica);

      // ACT: Reset
      control.network().reset(replica);
      sleep(500);

      // ASSERT: Ping time should return to baseline
      final long normalPing = measurePingTime(replica);
      System.out.println("After reset: " + normalPing + "ms (was: " + slowPing + "ms)");

      assertThat(normalPing)
          .isLessThan(slowPing - 30)
          .describedAs("Ping should be significantly faster after reset");
    }

    @Test
    @DisplayName("Should inject different latencies to different containers")
    void shouldInjectDifferentLatenciesToDifferentContainers() {
      // ACT: Inject 10ms to master, 100ms to replica
      control.network().injectLatency(master, Duration.ofMillis(10));
      control.network().injectLatency(replica, Duration.ofMillis(100));
      sleep(500);

      // ASSERT: Replica should be slower than master
      final long masterPing = measurePingTime(master);
      final long replicaPing = measurePingTime(replica);

      System.out.println("Master ping: " + masterPing + "ms, Replica ping: " + replicaPing + "ms");

      assertThat(replicaPing)
          .isGreaterThan(masterPing + 50)
          .describedAs("Replica (100ms) should be significantly slower than master (10ms)");
    }
  }

  // ==================== Packet Loss Injection Tests ====================

  @Nested
  @DisplayName("Packet Loss Injection (REAL)")
  class PacketLossTests {

    @Test
    @DisplayName("Should ACTUALLY drop packets with 30% loss rate")
    void shouldActuallyDropPackets() {
      // ACT: Inject 30% packet loss (high for testing)
      control.network().injectPacketLoss(replica, 0.3);
      sleep(500);

      // ASSERT: Packet loss should cause either failures OR increased latency
      // Note: Lettuce client may retry internally, so we check for either effect
      int failedPings = 0;
      long totalTime = 0;
      int successfulPings = 0;

      for (int i = 0; i < 50; i++) {
        final long start = System.currentTimeMillis();
        try {
          ping(replica);
          successfulPings++;
          totalTime += (System.currentTimeMillis() - start);
        } catch (Exception e) {
          failedPings++;
        }
      }

      final long avgTime = successfulPings > 0 ? totalTime / successfulPings : 0;

      System.out.println("Successful pings: " + successfulPings + ", Failed pings: " + failedPings);
      System.out.println("Average successful ping time: " + avgTime + "ms");

      // With 30% packet loss, expect EITHER failures OR increased latency (due to retransmissions)
      final boolean hasFailures = failedPings > 5;
      final boolean hasSlowPings = avgTime > 300; // Slow due to retransmissions (normal is <10ms)

      assertThat(hasFailures || hasSlowPings)
          .describedAs("Packet loss should cause either failures or slow pings (retransmissions)")
          .isTrue();
    }

    @Test
    @DisplayName("Should reset packet loss")
    void shouldResetPacketLoss() {
      // ARRANGE: Inject packet loss
      control.network().injectPacketLoss(replica, 0.3);
      sleep(500);

      // ACT: Reset
      control.network().reset(replica);
      sleep(500);

      // ASSERT: All pings should succeed
      int failures = 0;
      for (int i = 0; i < 20; i++) {
        try {
          ping(replica);
        } catch (Exception e) {
          failures++;
        }
      }

      assertThat(failures)
          .isLessThan(3)
          .describedAs("After reset, should have very few failures (max 2 out of 20)");
    }
  }

  // ==================== Jitter Injection Tests ====================

  @Nested
  @DisplayName("Jitter Injection (REAL)")
  class JitterTests {

    @Test
    @DisplayName("Should ACTUALLY create variable latency with jitter")
    void shouldActuallyCreateVariableLatency() {
      // ACT: Inject 50ms base latency with ±25ms jitter
      control
          .network()
          .injectLatencyWithJitter(replica, Duration.ofMillis(50), Duration.ofMillis(25));
      sleep(500);

      // ASSERT: Ping times should vary between ~25ms-75ms
      long minPing = Long.MAX_VALUE;
      long maxPing = Long.MIN_VALUE;

      for (int i = 0; i < 20; i++) {
        final long ping = measurePingTime(replica);
        minPing = Math.min(minPing, ping);
        maxPing = Math.max(maxPing, ping);
      }

      final long variance = maxPing - minPing;
      System.out.println(
          "Min ping: " + minPing + "ms, Max ping: " + maxPing + "ms, Variance: " + variance + "ms");

      // Should see variance (max - min should be significant)
      assertThat(variance)
          .isGreaterThan(15)
          .describedAs("Jitter should create at least 15ms variance");
    }
  }

  // ==================== Reset All Tests ====================

  @Nested
  @DisplayName("Reset All (REAL)")
  class ResetAllTests {

    @Test
    @DisplayName("Should reset all containers simultaneously")
    void shouldResetAllContainers() {
      // ARRANGE: Inject chaos to both containers
      control.network().injectLatency(master, Duration.ofMillis(100));
      control.network().injectLatency(replica, Duration.ofMillis(100));
      sleep(500);

      // Measure ping times with latency
      final long masterSlowPing = measurePingTime(master);
      final long replicaSlowPing = measurePingTime(replica);

      // ACT: Reset all
      control.network().resetAll();
      sleep(500);

      // ASSERT: Both should be faster than before (not absolute timing due to connection overhead)
      final long masterPing = measurePingTime(master);
      final long replicaPing = measurePingTime(replica);

      System.out.println("Master: " + masterSlowPing + "ms -> " + masterPing + "ms");
      System.out.println("Replica: " + replicaSlowPing + "ms -> " + replicaPing + "ms");

      // Verify latency decreased (connection overhead is high in containers, so we check relative
      // improvement)
      assertThat(masterPing)
          .isLessThan(masterSlowPing)
          .describedAs("Master should be faster after reset");
      assertThat(replicaPing)
          .isLessThan(replicaSlowPing)
          .describedAs("Replica should be faster after reset");
    }
  }

  // ==================== Helper Methods ====================

  /**
   * Measures PING round-trip time in milliseconds.
   *
   * @param container container to ping
   * @return ping time in milliseconds
   */
  private long measurePingTime(final GenericContainer<?> container) {
    final long start = System.currentTimeMillis();
    ping(container);
    return System.currentTimeMillis() - start;
  }

  /**
   * Pings Redis container.
   *
   * @param container container to ping
   * @throws RuntimeException if ping fails
   */
  private void ping(final GenericContainer<?> container) {
    final RedisURI uri =
        RedisURI.builder()
            .withHost(container.getHost())
            .withPort(container.getFirstMappedPort())
            .build();

    try (final RedisClient client = RedisClient.create(uri)) {
      client.connect().sync().ping();
    }
  }

  /**
   * Sleeps for specified milliseconds.
   *
   * @param millis milliseconds to sleep
   */
  private void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while sleeping", e);
    }
  }

  /**
   * Installs network tools (iproute2, iptables) in a Redis container.
   *
   * <p>Redis 7.4 Debian image is minimal and doesn't include tc/iptables by default.
   *
   * @param container the container to install tools in
   */
  private static void installNetworkTools(final GenericContainer<?> container) {
    PackageInstaller.install(container, "iproute2", "iptables");
  }
}
