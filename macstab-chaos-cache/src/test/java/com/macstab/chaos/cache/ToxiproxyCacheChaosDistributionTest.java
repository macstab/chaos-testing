/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import com.macstab.chaos.core.util.PackageInstaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;

/**
 * Comprehensive integration tests for {@link ToxiproxyCacheChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ToxiproxyCacheChaos - Comprehensive Tests")
class ToxiproxyCacheChaosDistributionTest {

  private GenericContainer<?> container;
  private ToxiproxyCacheChaos chaos;

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      if (chaos != null) {
        chaos.reset(container);
      }
      container.stop();
    }
  }

  // ==================== DISTRIBUTION TESTS ====================

  @Nested
  @DisplayName("Debian-based container (redis:7.4)")
  class DebianTests {

    @Test
    @DisplayName("should inject cache misses on Debian")
    void shouldInjectMissesDebian() throws Exception {
      container = createDebianRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      chaos.injectMisses(container, "user:*", 0.5);

      // Validate miss rate
      final double measured = measureMissRate(container, 100);
      assertThat(measured).isBetween(0.35, 0.65);
    }

    @Test
    @DisplayName("should force eviction on Debian")
    void shouldForceEvictionDebian() throws Exception {
      container = createDebianRedisContainer();
      populateCache(container, 100);
      chaos = new ToxiproxyCacheChaos();

      chaos.forceEviction(container, 50);

      final var dbsize = container.execInContainer("redis-cli", "-p", "6379", "DBSIZE");
      final int remaining = Integer.parseInt(dbsize.getStdout().trim());
      assertThat(remaining).isEqualTo(50);
    }
  }

  @Nested
  @DisplayName("Alpine-based container (redis:7.4-alpine)")
  class AlpineTests {

    @Test
    @DisplayName("should slow responses on Alpine")
    void shouldSlowResponseAlpine() throws Exception {
      container = createAlpineRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      // Measure baseline latency
      final long baseline = measureLatency(container, 10);
      
      chaos.slowResponse(container, Duration.ofMillis(200));

      // Measure latency after adding 200ms
      final long withLatency = measureLatency(container, 10);
      
      // Validate latency increased by ~200ms (allow 50ms tolerance)
      assertThat(withLatency - baseline).isBetween(150L, 250L);
    }
  }

  // ==================== POSITIVE TESTS ====================

  @Nested
  @DisplayName("Positive Scenarios")
  class PositiveTests {

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.1, 0.5, 0.9, 1.0})
    @DisplayName("should handle various miss rates")
    void shouldHandleVariousMissRates(double rate) throws Exception {
      container = createDebianRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      chaos.injectMisses(container, "test:*", rate);

      // Validate miss rate (allow 15% tolerance due to probability)
      final double measured = measureMissRate(container, 100);
      assertThat(measured).isBetween(rate - 0.15, rate + 0.15);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 50, 99, 100})
    @DisplayName("should handle various eviction percentages")
    void shouldHandleVariousEvictionPercentages(int percentage) throws Exception {
      container = createDebianRedisContainer();
      populateCache(container, 100);
      chaos = new ToxiproxyCacheChaos();

      chaos.forceEviction(container, percentage);

      // Validate eviction happened
      final var dbsize = container.execInContainer("redis-cli", "-p", "6379", "DBSIZE");
      final int remaining = Integer.parseInt(dbsize.getStdout().trim());
      assertThat(remaining).isEqualTo(100-percentage);
    }

    @Test
    @DisplayName("should combine misses and slow response")
    void shouldCombineMissesAndSlowResponse() throws Exception {
      container = createDebianRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      final long baseline = measureLatency(container, 10);

      chaos.injectMisses(container, "user:*", 0.3);
      chaos.slowResponse(container, Duration.ofMillis(100));

      // Validate both chaos applied
      final double missRate = measureMissRate(container, 50);
      final long latency = measureLatency(container, 10);
      
      assertThat(missRate).isBetween(0.15, 0.45);
      assertThat(latency - baseline).isBetween(50L, 150L);
    }
  }

  // ==================== NEGATIVE TESTS ====================

  @Nested
  @DisplayName("Negative Scenarios")
  class NegativeTests {

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      chaos = new ToxiproxyCacheChaos();

      assertThatThrownBy(() -> chaos.injectMisses(null, "test:*", 0.5))
          .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -0.1, 1.1, 2.0})
    @DisplayName("should reject invalid miss rates")
    void shouldRejectInvalidMissRates(double rate) throws Exception {
      container = createDebianRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      assertThatThrownBy(() -> chaos.injectMisses(container, "test:*", rate))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, 200})
    @DisplayName("should reject invalid eviction percentages")
    void shouldRejectInvalidEvictionPercentages(int percentage) throws Exception {
      container = createDebianRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      assertThatThrownBy(() -> chaos.forceEviction(container, percentage))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject stopped container")
    void shouldRejectStoppedContainer() throws Exception {
      container = createDebianRedisContainer();
      container.stop();
      chaos = new ToxiproxyCacheChaos();

      assertThatThrownBy(() -> chaos.injectMisses(container, "test:*", 0.5))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ==================== EDGE CASES ====================

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("should handle eviction on empty cache")
    void shouldHandleEvictionOnEmptyCache() throws Exception {
      container = createDebianRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      chaos.forceEviction(container, 50);

      // Validate cache still empty
      final var dbsize = container.execInContainer("redis-cli", "-p", "6379", "DBSIZE");
      final int remaining = Integer.parseInt(dbsize.getStdout().trim());
      assertThat(remaining).isEqualTo(0);
    }

    @Test
    @DisplayName("should handle repeated reset")
    void shouldHandleRepeatedReset() throws Exception {
      container = createDebianRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      chaos.injectMisses(container, "test:*", 0.5);
      chaos.reset(container);
      chaos.reset(container);
      chaos.reset(container);

      assertThat(isToxiproxyRunning(container)).isFalse();
    }

    @Test
    @DisplayName("should handle very high latency")
    void shouldHandleVeryHighLatency() throws Exception {
      container = createDebianRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      final long baseline = measureLatency(container, 5);
      
      chaos.slowResponse(container, Duration.ofSeconds(5));

      final long withLatency = measureLatency(container, 5);
      
      // Validate latency increased by ~5000ms (allow 500ms tolerance)
      assertThat(withLatency - baseline).isBetween(4500L, 5500L);
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
      chaos = new ToxiproxyCacheChaos();

      chaos.injectMisses(container, "test:*", 0.5);
      chaos.reset(container);

      final var result = container.execInContainer("iptables", "-t", "nat", "-L", "OUTPUT");
      assertThat(result.getStdout()).doesNotContain("REDIRECT");
    }

    @Test
    @DisplayName("should stop Toxiproxy")
    void shouldStopToxiproxy() throws Exception {
      container = createDebianRedisContainer();
      chaos = new ToxiproxyCacheChaos();

      chaos.injectMisses(container, "test:*", 0.5);
      chaos.reset(container);

      assertThat(isToxiproxyRunning(container)).isFalse();
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

  private void populateCache(GenericContainer<?> container, int keyCount) throws Exception {
    for (int i = 0; i < keyCount; i++) {
      container.execInContainer("redis-cli", "-p", "6379", "SET", "key" + i, "value" + i);
    }
  }

  /** Check if Toxiproxy is running by checking process. */
  private boolean isToxiproxyRunning(GenericContainer<?> container) throws Exception {
    PackageInstaller.install(container, List.of("procps"), false);
    // Simple check: ps + grep without filtering grep itself
    final var psCheck = container.execInContainer(
        "sh", "-c", "ps aux | grep toxiproxy-server | grep -v grep");
    
    return psCheck.getExitCode() == 0;
  }

  /** Measure cache miss rate through Toxiproxy. Returns failure rate (0.0-1.0). */
  private double measureMissRate(GenericContainer<?> container, int attempts) throws Exception {
    // Set a test key
    container.execInContainer("redis-cli", "-p", "6379", "SET", "test:key", "value");
    
    int failures = 0;
    for (int i = 0; i < attempts; i++) {
      // GET through proxy (timeout toxic causes failures)
      final var result = container.execInContainer(
          "sh", "-c", 
          "redis-cli -p 16379 --raw GET test:key 2>&1 | grep -q 'timeout\\|error\\|refused' && echo fail || echo ok");
      
      if ("fail".equals(result.getStdout().trim())) {
        failures++;
      }
    }
    
    return (double) failures / attempts;
  }

  /** Measure average GET latency in milliseconds. Uses proxy port if available, direct otherwise. */
  private long measureLatency(GenericContainer<?> container, int attempts) throws Exception {
    // Set a test key via direct port
    container.execInContainer("redis-cli", "-p", "6379", "SET", "test:latency", "value");
    
    // Check if proxy is available (port 16379)
    final var proxyCheck = container.execInContainer(
        "sh", "-c", "timeout 1 redis-cli -p 16379 PING 2>/dev/null || echo NOTAVAIL");
    final boolean useProxy = !"NOTAVAIL".equals(proxyCheck.getStdout().trim());
    final int port = useProxy ? 16379 : 6379;
    
    long totalMs = 0;
    for (int i = 0; i < attempts; i++) {
      final long start = System.currentTimeMillis();
      container.execInContainer("redis-cli", "-p", String.valueOf(port), "GET", "test:latency");
      final long end = System.currentTimeMillis();
      totalMs += (end - start);
    }
    
    return totalMs / attempts;
  }
}
