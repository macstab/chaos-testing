/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
import com.macstab.chaos.network.control.NetworkChaosController;
import com.macstab.chaos.network.exception.NetworkChaosException;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

/**
 * Integration tests for network chaos engineering features.
 *
 * <p><strong>Requirements:</strong>
 *
 * <ul>
 *   <li>Linux environment (native host, dev container, or CI)
 *   <li>NET_ADMIN capability on test containers (automatically added)
 *   <li>Docker support for network namespace manipulation
 * </ul>
 *
 * <p><strong>What This Tests:</strong>
 *
 * <ul>
 *   <li>✅ Latency injection with tc netem
 *   <li>✅ Packet loss simulation
 *   <li>✅ Network jitter
 *   <li>✅ Network partitions with iptables
 *   <li>✅ Chaos reset functionality
 * </ul>
 *
 * <p><strong>Note:</strong> Tests are automatically disabled on macOS/Windows host using
 * {@code @DisabledOnNonLinuxHost}. They run successfully in dev containers and CI.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisabledOnNonLinuxHost("Network chaos requires Linux host or dev container")
@DisplayName("Network Chaos Engineering Integration Tests")
class NetworkChaosIntegrationTest {

  // ==================== Test Constants ====================

  private static final String REDIS_IMAGE = "redis:7.4";
  private static final int REDIS_PORT = 6379;
  private static final String REDIS_PROTECTED_MODE_ARG = "--protected-mode";
  private static final String REDIS_PROTECTED_MODE_NO = "no";
  private static final String REDIS_REPLICAOF_ARG = "--replicaof";

  // Timing constants (avoid magic values)
  private static final Duration WARMUP_DELAY = Duration.ofMillis(100);
  private static final Duration TC_RULE_APPLY_DELAY = Duration.ofMillis(200);
  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration AWAIT_POLL_INTERVAL = Duration.ofMillis(50);

  // Latency test constants
  private static final Duration LATENCY_100MS = Duration.ofMillis(100);
  private static final long MIN_LATENCY_INCREASE_MS = 50L;
  private static final long MIN_LATENCY_DECREASE_MS = 50L;

  // Packet loss test constants
  private static final double HIGH_PACKET_LOSS_RATE = 0.5;
  private static final int PACKET_LOSS_TEST_ITERATIONS = 30;
  private static final int MIN_EXPECTED_FAILURES = 3;
  private static final long SLOW_PING_THRESHOLD_MS = 800L;

  // Jitter test constants
  private static final Duration JITTER_BASE_LATENCY = Duration.ofMillis(50);
  private static final Duration JITTER_VARIANCE = Duration.ofMillis(25);
  private static final long MIN_JITTER_VARIANCE_MS = 20L;
  private static final int JITTER_SAMPLE_SIZE = 10;

  // Invalid test constants
  private static final Duration NEGATIVE_DURATION = Duration.ofMillis(-100);
  private static final double INVALID_PACKET_LOSS_HIGH = 1.5;
  private static final double INVALID_PACKET_LOSS_NEGATIVE = -0.1;

  // ==================== Test Infrastructure ====================

  private static Network network;
  private static GenericContainer<?> master;
  private static GenericContainer<?> replica;
  private static ControlFacade control;

  @BeforeAll
  static void setup() {
    network = Network.newNetwork();

    // Create master with NET_ADMIN capability
    master =
        new GenericContainer<>(REDIS_IMAGE)
            .withNetwork(network)
            .withExposedPorts(REDIS_PORT)
            .withCommand("redis-server", REDIS_PROTECTED_MODE_ARG, REDIS_PROTECTED_MODE_NO)
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    master.start();

    // Install network tools (tc, iptables) in master
    installNetworkTools(master);

    // Create replica with NET_ADMIN capability
    final String masterIp = getContainerIp(master);

    replica =
        new GenericContainer<>(REDIS_IMAGE)
            .withNetwork(network)
            .withExposedPorts(REDIS_PORT)
            .withCommand(
                "redis-server",
                REDIS_PROTECTED_MODE_ARG,
                REDIS_PROTECTED_MODE_NO,
                REDIS_REPLICAOF_ARG,
                masterIp,
                String.valueOf(REDIS_PORT))
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    replica.start();

    // Install network tools (tc, iptables) in replica
    installNetworkTools(replica);

    // Create ControlFacade
    final var allContainers = List.of(master, replica);
    final var containerIndexMap = java.util.Map.of(master, 0, replica, 1);
    control = ControlFacade.create(allContainers, containerIndexMap);
  }

  @AfterEach
  void cleanup() {
    // Reset all network chaos after each test
    control.network().resetAll();
  }

  @AfterAll
  static void tearDown() {
    if (master != null) {
      master.stop();
    }
    if (replica != null) {
      replica.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  // ==================== Latency Injection Tests ====================

  @Nested
  @DisplayName("Latency Injection")
  class LatencyInjectionTests {

    @Nested
    @DisplayName("Happy Path")
    class HappyPath {

      @Test
      @DisplayName("Should inject 100ms latency and verify ping time increases by at least 50ms")
      void shouldInjectLatency() {
        // Arrange: Establish baseline ping time
        final long baselinePing = establishBaselinePing(replica);

        // Act: Inject 100ms latency
        control.network().injectLatency(replica, LATENCY_100MS);

        // Assert: Ping time should increase by at least 50ms
        await()
            .atMost(AWAIT_TIMEOUT)
            .pollInterval(AWAIT_POLL_INTERVAL)
            .untilAsserted(
                () -> {
                  final long slowPing = measurePingTime(replica);
                  final long increase = slowPing - baselinePing;
                  assertThat(increase)
                      .as(
                          "Latency should increase by at least %dms (baseline: %dms, current: %dms)",
                          MIN_LATENCY_INCREASE_MS, baselinePing, slowPing)
                      .isGreaterThan(MIN_LATENCY_INCREASE_MS);
                });
      }

      @Test
      @DisplayName("Should reset latency injection and return to baseline ping times")
      void shouldResetLatencyInjection() {
        // Arrange: Inject latency and measure slow ping
        control.network().injectLatency(replica, LATENCY_100MS);
        sleep(TC_RULE_APPLY_DELAY);
        final long slowPing = measurePingTime(replica);

        // Act: Reset network chaos
        control.network().reset(replica);

        // Assert: Ping time should return to near baseline
        await()
            .atMost(AWAIT_TIMEOUT)
            .pollInterval(AWAIT_POLL_INTERVAL)
            .untilAsserted(
                () -> {
                  final long normalPing = measurePingTime(replica);
                  assertThat(normalPing)
                      .as(
                          "Ping should return to baseline after network reset (slow: %dms, current: %dms)",
                          slowPing, normalPing)
                      .isLessThan(slowPing - MIN_LATENCY_DECREASE_MS);
                });
      }
    }

    @Nested
    @DisplayName("Error Cases")
    class ErrorCases {

      @Test
      @DisplayName("Should reject negative latency with IllegalArgumentException")
      void shouldRejectNegativeLatency() {
        assertThatThrownBy(() -> control.network().injectLatency(replica, NEGATIVE_DURATION))
            .as("Negative latency should be rejected")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
      }
    }
  }

  // ==================== Packet Loss Injection Tests ====================

  @Nested
  @DisplayName("Packet Loss Injection")
  class PacketLossInjectionTests {

    @Nested
    @DisplayName("Happy Path")
    class HappyPath {

      @Test
      @DisplayName(
          "Should inject 50% packet loss and observe either ping failures or increased latency")
      void shouldInjectPacketLoss() {
        // Act: Inject 50% packet loss (high for testing)
        control.network().injectPacketLoss(replica, HIGH_PACKET_LOSS_RATE);
        sleep(TC_RULE_APPLY_DELAY);

        // Assert: Some pings should fail or take longer
        final AtomicInteger failedPings = new AtomicInteger(0);
        final AtomicLong totalTime = new AtomicLong(0);

        for (int i = 0; i < PACKET_LOSS_TEST_ITERATIONS; i++) {
          final long start = System.currentTimeMillis();
          try {
            ping(replica);
            totalTime.addAndGet(System.currentTimeMillis() - start);
          } catch (Exception e) {
            failedPings.incrementAndGet();
          }
        }

        final int successfulPings = PACKET_LOSS_TEST_ITERATIONS - failedPings.get();
        final long avgTime = successfulPings > 0 ? totalTime.get() / successfulPings : 0;

        // With 50% packet loss, expect EITHER failures OR increased latency (due to
        // retransmissions)
        final boolean hasFailures = failedPings.get() > MIN_EXPECTED_FAILURES;
        final boolean hasSlowPings = avgTime > SLOW_PING_THRESHOLD_MS;

        assertThat(hasFailures || hasSlowPings)
            .as(
                "50%% packet loss should cause either failures (%d/%d) or slow pings (%dms avg)",
                failedPings.get(), PACKET_LOSS_TEST_ITERATIONS, avgTime)
            .isTrue();
      }
    }

    @Nested
    @DisplayName("Error Cases")
    class ErrorCases {

      @Test
      @DisplayName("Should reject packet loss > 1.0 with IllegalArgumentException")
      void shouldRejectHighPacketLoss() {
        assertThatThrownBy(
                () -> control.network().injectPacketLoss(replica, INVALID_PACKET_LOSS_HIGH))
            .as("Packet loss > 1.0 should be rejected")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be in [0.0, 1.0]");
      }

      @Test
      @DisplayName("Should reject negative packet loss with IllegalArgumentException")
      void shouldRejectNegativePacketLoss() {
        assertThatThrownBy(
                () -> control.network().injectPacketLoss(replica, INVALID_PACKET_LOSS_NEGATIVE))
            .as("Negative packet loss should be rejected")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be in [0.0, 1.0]");
      }
    }
  }

  // ==================== Jitter Injection Tests ====================

  @Nested
  @DisplayName("Jitter Injection")
  class JitterInjectionTests {

    @Nested
    @DisplayName("Happy Path")
    class HappyPath {

      @Test
      @DisplayName("Should inject 50ms ±25ms jitter and observe ping time variance")
      void shouldInjectLatencyWithJitter() {
        // Act: Inject 50ms base latency with ±25ms jitter
        control.network().injectLatencyWithJitter(replica, JITTER_BASE_LATENCY, JITTER_VARIANCE);
        sleep(TC_RULE_APPLY_DELAY);

        // Assert: Ping times should vary significantly
        long minPing = Long.MAX_VALUE;
        long maxPing = Long.MIN_VALUE;

        for (int i = 0; i < JITTER_SAMPLE_SIZE; i++) {
          final long ping = measurePingTime(replica);
          minPing = Math.min(minPing, ping);
          maxPing = Math.max(maxPing, ping);
        }

        final long variance = maxPing - minPing;
        assertThat(variance)
            .as(
                "Jitter should cause at least %dms variance in ping times (min: %dms, max: %dms)",
                MIN_JITTER_VARIANCE_MS, minPing, maxPing)
            .isGreaterThan(MIN_JITTER_VARIANCE_MS);
      }
    }

    @Nested
    @DisplayName("Error Cases")
    class ErrorCases {

      @Test
      @DisplayName("Should reject negative jitter with IllegalArgumentException")
      void shouldRejectNegativeJitter() {
        assertThatThrownBy(
                () ->
                    control
                        .network()
                        .injectLatencyWithJitter(
                            replica, JITTER_BASE_LATENCY, Duration.ofMillis(-10)))
            .as("Negative jitter should be rejected")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
      }
    }
  }

  // ==================== Network Partition Tests ====================

  @Nested
  @DisplayName("Network Partition")
  class NetworkPartitionTests {

    @Nested
    @DisplayName("Happy Path")
    class HappyPath {

      @Test
      @DisplayName("Should create network partition and verify containers remain running")
      void shouldCreateNetworkPartition() {
        // Act: Partition replica from master
        control.network().partitionFrom(replica, master);

        // Assert: Both containers should still be running (partition created successfully)
        assertThat(replica.isRunning())
            .as("Replica should still be running after partition")
            .isTrue();
        assertThat(master.isRunning())
            .as("Master should still be running after partition")
            .isTrue();
      }
    }
  }

  // ==================== Reset All Tests ====================

  @Nested
  @DisplayName("Reset All Containers")
  class ResetAllTests {

    @Nested
    @DisplayName("Happy Path")
    class HappyPath {

      @Test
      @DisplayName("Should reset network chaos on all containers and verify latency decreases")
      void shouldResetAllContainers() {
        // Arrange: Inject chaos to both containers
        control.network().injectLatency(master, LATENCY_100MS);
        control.network().injectLatency(replica, LATENCY_100MS);
        sleep(TC_RULE_APPLY_DELAY);

        final long masterSlowPing = measurePingTime(master);
        final long replicaSlowPing = measurePingTime(replica);

        // Act: Reset all
        control.network().resetAll();
        sleep(TC_RULE_APPLY_DELAY);

        // Assert: Both should be faster than before
        await()
            .atMost(AWAIT_TIMEOUT)
            .pollInterval(AWAIT_POLL_INTERVAL)
            .untilAsserted(
                () -> {
                  final long masterPing = measurePingTime(master);
                  final long replicaPing = measurePingTime(replica);

                  assertThat(masterPing)
                      .as(
                          "Master should be faster after reset (slow: %dms, current: %dms)",
                          masterSlowPing, masterPing)
                      .isLessThan(masterSlowPing);

                  assertThat(replicaPing)
                      .as(
                          "Replica should be faster after reset (slow: %dms, current: %dms)",
                          replicaSlowPing, replicaPing)
                      .isLessThan(replicaSlowPing);
                });
      }
    }
  }

  // ==================== Error Handling Tests ====================

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandlingTests {

    @Nested
    @DisplayName("Missing Capabilities")
    class MissingCapabilities {

      @Test
      @DisplayName(
          "Should throw NetworkChaosException with NET_ADMIN message for container without capability")
      void shouldHandleMissingNetAdminCapability() {
        // Create container WITHOUT NET_ADMIN capability (but with network tools)
        final GenericContainer<?> containerNoNetAdmin =
            new GenericContainer<>(REDIS_IMAGE)
                .withNetwork(network)
                .withExposedPorts(REDIS_PORT)
                .withCommand("redis-server", REDIS_PROTECTED_MODE_ARG, REDIS_PROTECTED_MODE_NO);
        containerNoNetAdmin.start();

        // Install network tools so tc command exists (but will fail without NET_ADMIN)
        installNetworkTools(containerNoNetAdmin);

        try {
          // Act & Assert: Should throw NetworkChaosException with helpful NET_ADMIN error message
          assertThatThrownBy(
                  () ->
                      new NetworkChaosController(List.of(containerNoNetAdmin))
                          .injectLatency(containerNoNetAdmin, LATENCY_100MS))
              .as("Missing NET_ADMIN capability should throw NetworkChaosException")
              .isInstanceOf(NetworkChaosException.class)
              .hasMessageContaining("NET_ADMIN");
        } finally {
          containerNoNetAdmin.stop();
        }
      }
    }
  }

  // ==================== Helper Methods ====================

  /**
   * Establishes a stable baseline ping time by warming up connection and averaging samples.
   *
   * @param container container to ping
   * @return baseline ping time in milliseconds
   */
  private static long establishBaselinePing(final GenericContainer<?> container) {
    // Warm up connection pool
    measurePingTime(container);
    sleep(WARMUP_DELAY);

    // Take baseline measurement
    return measurePingTime(container);
  }

  /**
   * Measures PING round-trip time in milliseconds.
   *
   * @param container container to ping
   * @return ping time in milliseconds
   */
  private static long measurePingTime(final GenericContainer<?> container) {
    final long start = System.currentTimeMillis();
    ping(container);
    return System.currentTimeMillis() - start;
  }

  /**
   * Pings Redis container.
   *
   * @param container container to ping
   */
  private static void ping(final GenericContainer<?> container) {
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
   * Sleeps for specified duration.
   *
   * @param duration duration to sleep
   */
  private static void sleep(final Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
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

  /**
   * Gets container IP address from Docker network.
   *
   * @param container container
   * @return IP address
   */
  private static String getContainerIp(final GenericContainer<?> container) {
    return container
        .getContainerInfo()
        .getNetworkSettings()
        .getNetworks()
        .values()
        .iterator()
        .next()
        .getIpAddress();
  }
}
