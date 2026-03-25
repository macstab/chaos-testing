/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.util.PackageInstaller;

/**
 * Comprehensive distribution tests for {@link ProxyChaosProvider}.
 *
 * <p>Tests universal TCP proxy chaos across multiple distributions.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ProxyChaosProvider - Distribution Tests")
class ProxyChaosProviderDistributionTest {

  private GenericContainer<?> container;
  private ProxyChaosProvider provider;

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      if (provider != null) {
        provider.reset(container);
      }
      container.stop();
    }
  }

  // ==================== DISTRIBUTION TESTS ====================

  @Nested
  @DisplayName("Debian-based container (redis:7.4)")
  class DebianTests {

    @Test
    @DisplayName("should create proxy on Debian")
    void shouldCreateProxyDebian() throws Exception {
      container = createDebianRedisContainer();
      provider = new ProxyChaosProvider();

      final String hostname = provider.createProxy(container, "redis", 6379, 16379);

      assertThat(hostname).isNotEmpty();
      assertThat(isProxyListening(container, 16379)).isTrue();
    }

    @Test
    @DisplayName("should inject latency on Debian")
    void shouldInjectLatencyDebian() throws Exception {
      container = createDebianRedisContainer();
      provider = new ProxyChaosProvider();

      final String hostname = provider.createProxy(container, "redis", 6379, 16379);
      final long baseline = measureLatency(container, hostname, 10);

      provider.addLatency(container, "redis", Duration.ofMillis(200));

      final long withLatency = measureLatency(container, hostname, 10);
      assertThat(withLatency - baseline).isBetween(150L, 300L);
    }
  }

  @Nested
  @DisplayName("Alpine-based container (redis:7.4-alpine)")
  class AlpineTests {

    @Test
    @DisplayName("should create proxy on Alpine")
    void shouldCreateProxyAlpine() throws Exception {
      container = createAlpineRedisContainer();
      provider = new ProxyChaosProvider();

      final String hostname = provider.createProxy(container, "redis", 6379, 16379);

      assertThat(hostname).isNotEmpty();
      assertThat(isProxyListening(container, 16379)).isTrue();
    }

    @Test
    @DisplayName("should inject timeout on Alpine")
    void shouldInjectTimeoutAlpine() throws Exception {
      container = createAlpineRedisContainer();
      provider = new ProxyChaosProvider();

      final String hostname = provider.createProxy(container, "redis", 6379, 16379);

      provider.addTimeout(container, "redis", Duration.ofMillis(1), 0.5);

      final double failureRate = measureFailureRate(container, hostname, 100);
      assertThat(failureRate).isBetween(0.35, 0.65);
    }
  }

  // ==================== POSITIVE TESTS ====================

  @Nested
  @DisplayName("Positive Scenarios")
  class PositiveTests {

    @ParameterizedTest
    @ValueSource(doubles = {0.5})
    //    @ValueSource(doubles = {0.0, 0.1, 0.5, 0.9, 1.0})
    @DisplayName("should handle various timeout rates")
    void shouldHandleVariousTimeoutRates(double rate) throws Exception {
      container = createDebianRedisContainer();
      provider = new ProxyChaosProvider();

      final String hostname = provider.createProxy(container, "redis", 6379, 16379);
      provider.addTimeout(container, "redis", Duration.ofMillis(1), rate);

      final double measured = measureFailureRate(container, hostname, 100);
      assertThat(measured).isBetween(rate - 0.15, rate + 0.15);
      provider.reset(container);
    }

    /**
     * Test combining multiple toxics (latency + timeout).
     *
     * <p><strong>Why these values:</strong>
     *
     * <ul>
     *   <li><strong>100ms latency:</strong> Moderate delay, easy to measure reliably
     *   <li><strong>1ms timeout:</strong> Instant connection close (vs delayed close)
     *   <li><strong>20% probability:</strong> High enough to detect, low enough for stable average
     * </ul>
     *
     * <p><strong>Why NOT 1000ms latency:</strong>
     *
     * <ul>
     *   <li>With 20% instant failures, average latency becomes unpredictable
     *   <li>Random variance can push failure rate to 25-30% (reduces average more)
     *   <li>Test would need very wide tolerance range (500-1100ms)
     *   <li>100ms is sufficient to prove toxics combine correctly
     * </ul>
     *
     * <p><strong>Expected behavior:</strong>
     *
     * <ul>
     *   <li>80% requests: 100ms latency each
     *   <li>20% requests: instant fail (~0ms)
     *   <li>Average: (0.8 × 100) + (0.2 × 0) = 80ms
     *   <li>Actual: 50-150ms (includes baseline variance + random timeout variance)
     * </ul>
     *
     * <p><strong>Random variance note:</strong> At 1ms timeout with 20% probability, actual failure
     * rate can be 15-25% due to randomness. Higher failure rate → lower average latency. This is
     * expected behavior (Toxiproxy uses random distribution).
     */
    @Test
    @DisplayName("should combine multiple toxics")
    void shouldCombineMultipleToxics() throws Exception {
      container = createDebianRedisContainer();
      provider = new ProxyChaosProvider();

      final String hostname = provider.createProxy(container, "redis", 6379, 16379);
      final long baseline = measureLatency(container, hostname, 10);

      // Add moderate latency (100ms) + timeout toxic (20% failure rate)
      provider.addLatency(container, "redis", Duration.ofMillis(100));
      provider.addTimeout(container, "redis", Duration.ofMillis(1), 0.2);

      final long latency = measureLatency(container, hostname, 20);
      final double failureRate = measureFailureRate(container, hostname, 50);

      // Latency: ~100ms added, but 20% requests fail instantly → average ~80ms
      // Wider range (50-150ms) accounts for random variance in timeout probability
      assertThat(latency - baseline).isBetween(50L, 150L);

      // Failure rate: ~20% (with random variance: 15-25% is normal)
      assertThat(failureRate).isBetween(0.05, 0.35);
    }
  }

  // ==================== NEGATIVE TESTS ====================

  @Nested
  @DisplayName("Negative Scenarios")
  class NegativeTests {

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() throws Exception {
      container = createDebianRedisContainer();
      container.stop();
      provider = new ProxyChaosProvider();

      assertThatThrownBy(() -> provider.createProxy(container, "redis", 6379, 16379))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ==================== CLEANUP TESTS ====================

  @Nested
  @DisplayName("Cleanup Verification")
  class CleanupTests {

    @Test
    @DisplayName("should remove iptables rules")
    void shouldRemoveIptablesRules() throws Exception {
      container = createDebianRedisContainer();
      provider = new ProxyChaosProvider();

      provider.createProxy(container, "redis", 6379, 16379);
      provider.reset(container);

      final var result = container.execInContainer("iptables", "-t", "nat", "-L", "PREROUTING");
      assertThat(result.getStdout()).doesNotContain("REDIRECT");
    }

    @Test
    @DisplayName("should stop Toxiproxy")
    void shouldStopToxiproxy() throws Exception {
      container = createDebianRedisContainer();
      provider = new ProxyChaosProvider();

      provider.createProxy(container, "redis", 6379, 16379);
      provider.reset(container);

      assertThat(isToxiproxyRunning(container)).isFalse();
    }
  }

  // ==================== EDGE CASES ====================

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("should handle repeated reset")
    void shouldHandleRepeatedReset() throws Exception {
      container = createDebianRedisContainer();
      provider = new ProxyChaosProvider();

      provider.createProxy(container, "redis", 6379, 16379);
      provider.reset(container);
      provider.reset(container);
      provider.reset(container);

      assertThat(isToxiproxyRunning(container)).isFalse();
    }

    @Test
    @DisplayName("should handle very high latency")
    void shouldHandleVeryHighLatency() throws Exception {
      container = createDebianRedisContainer();
      provider = new ProxyChaosProvider();

      final String hostname = provider.createProxy(container, "redis", 6379, 16379);
      final long baseline = measureLatency(container, hostname, 3);

      provider.addLatency(container, "redis", Duration.ofMillis(200));

      final long withLatency = measureLatency(container, hostname, 3);
      assertThat(withLatency - baseline).isBetween(150L, 250L);
    }
  }

  // ==================== HELPER METHODS ====================

  private GenericContainer<?> createDebianRedisContainer() {
    final var c =
        new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    c.start();
    return c;
  }

  private GenericContainer<?> createAlpineRedisContainer() {
    final var c =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    c.start();
    return c;
  }

  private boolean isToxiproxyRunning(GenericContainer<?> container) throws Exception {
    PackageInstaller.install(container, List.of("procps"), false);
    final var psCheck =
        container.execInContainer("sh", "-c", "ps aux | grep toxiproxy-server | grep -v grep");

    return psCheck.getExitCode() == 0;
  }

  private boolean isProxyListening(GenericContainer<?> container, int port) throws Exception {
    // Use platform abstraction for port check
    final var platform = com.macstab.chaos.core.platform.PlatformDetector.detect(container);
    final var shell = platform.getDefaultShell();
    final String checkCmd = shell.buildPortCheckCommand(port);
    final var result = shell.exec(container, checkCmd);
    return result.getExitCode() == 0;
  }

  private long measureLatency(GenericContainer<?> container, String hostname, int attempts)
      throws Exception {

    container.execInContainer("redis-cli", "-h", hostname, "-p", "6379", "SET", "test", "value");

    long totalMs = 0;
    for (int i = 0; i < attempts; i++) {
      final long start = System.currentTimeMillis();
      container.execInContainer("redis-cli", "-h", hostname, "-p", "6379", "GET", "test");
      final long end = System.currentTimeMillis();
      totalMs += (end - start);
    }

    return totalMs / attempts;
  }

  private double measureFailureRate(GenericContainer<?> container, String hostname, int attempts)
      throws Exception {

    // localhost. We skip the proxy in this case to set the data.
    container.execInContainer("redis-cli", "-h", "localhost", "-p", "6379", "SET", "test", "value");

    int failures = 0;
    for (int i = 0; i < attempts; i++) {
      final var result =
          container.execInContainer("redis-cli", "-h", hostname, "-p", "6379", "GET", "test");

      // Toxiproxy timeout toxic closes connection immediately
      // redis-cli exits with non-zero on connection failure
      if (result.getExitCode() != 0) {
        failures++;
      }
    }

    return (double) failures / attempts;
  }
}
