/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;
import com.macstab.chaos.proxy.internal.operations.toxic.LatencyToxic;

/**
 * Comprehensive integration tests for ToxiproxyOrchestrator.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("ToxiproxyOrchestrator")
class ToxiproxyOrchestratorTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4").withExposedPorts(6379);

  private final ToxiproxyConfig config = ToxiproxyConfig.defaults();
  private final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);

  @AfterEach
  void cleanup() {
    orchestrator.reset(REDIS);
  }

  @Nested
  @DisplayName("Full Lifecycle Integration")
  class FullLifecycleTests {

    @Test
    @DisplayName("should orchestrate full chaos lifecycle")
    void shouldOrchestrateFullLifecycle() {
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      // 1. Start Toxiproxy
      orchestrator.start(REDIS);
      assertThat(orchestrator.isRunning(REDIS)).isTrue();

      // 2. Create proxy
      String hostname = orchestrator.createProxy(REDIS, proxyConfig);
      assertThat(hostname).isNotNull();
      assertThat(orchestrator.proxyExists(REDIS, "redis")).isTrue();

      // 3. Add toxic
      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();
      orchestrator.addToxic(REDIS, "redis", toxic);

      // 4. Remove toxic
      orchestrator.removeToxic(REDIS, "redis", "latency");

      // 5. Delete proxy
      orchestrator.deleteProxy(REDIS, "redis");
      assertThat(orchestrator.proxyExists(REDIS, "redis")).isFalse();

      // 6. Stop
      orchestrator.stop(REDIS);
      assertThat(orchestrator.isRunning(REDIS)).isFalse();
    }

    @Test
    @DisplayName("should handle rapid lifecycle iterations")
    void shouldHandleRapidIterations() {
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      for (int i = 0; i < 5; i++) {
        orchestrator.start(REDIS);
        orchestrator.createProxy(REDIS, proxyConfig);
        orchestrator.deleteProxy(REDIS, "redis");
        orchestrator.stop(REDIS);
      }

      assertThat(orchestrator.isRunning(REDIS)).isFalse();
    }
  }

  @Nested
  @DisplayName("Proxy Operations")
  class ProxyOperationsTests {

    @Test
    @DisplayName("should create and manage multiple proxies")
    void shouldManageMultipleProxies() {
      orchestrator.start(REDIS);

      ProxyConfiguration redis1 = new ProxyConfiguration("redis-1", 6379, 16379);
      ProxyConfiguration redis2 = new ProxyConfiguration("redis-2", 6379, 17379);

      orchestrator.createProxy(REDIS, redis1);
      orchestrator.createProxy(REDIS, redis2);

      assertThat(orchestrator.proxyExists(REDIS, "redis-1")).isTrue();
      assertThat(orchestrator.proxyExists(REDIS, "redis-2")).isTrue();
    }

    @Test
    @DisplayName("should handle proxy creation without explicit start")
    void shouldAutoStartOnProxyCreation() {
      // Don't call start() explicitly
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      // createProxy should auto-start if needed
      String hostname = orchestrator.createProxy(REDIS, proxyConfig);

      assertThat(hostname).isNotNull();
      assertThat(orchestrator.isRunning(REDIS)).isTrue();
    }
  }

  @Nested
  @DisplayName("Toxic Operations")
  class ToxicOperationsTests {

    @Test
    @DisplayName("should add and remove toxics")
    void shouldAddAndRemoveToxics() {
      orchestrator.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      orchestrator.createProxy(REDIS, proxyConfig);

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(500).build();

      orchestrator.addToxic(REDIS, "redis", toxic);
      orchestrator.removeToxic(REDIS, "redis", "latency");

      // No exception = success
    }

    @Test
    @DisplayName("should manage multiple toxics on same proxy")
    void shouldManageMultipleToxics() {
      orchestrator.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      orchestrator.createProxy(REDIS, proxyConfig);

      orchestrator.addToxic(
          REDIS, "redis", LatencyToxic.builder().name("latency-1").latencyMs(100).build());
      orchestrator.addToxic(
          REDIS, "redis", LatencyToxic.builder().name("latency-2").latencyMs(200).build());

      orchestrator.removeAllToxics(REDIS, "redis");

      // No exception = success
    }
  }

  @Nested
  @DisplayName("Reset Operations")
  class ResetTests {

    @Test
    @DisplayName("should reset all state")
    void shouldResetAllState() {
      orchestrator.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      orchestrator.createProxy(REDIS, proxyConfig);
      orchestrator.addToxic(
          REDIS, "redis", LatencyToxic.builder().name("latency").latencyMs(100).build());

      // Reset
      orchestrator.reset(REDIS);

      // Verify: Toxiproxy stopped, proxies deleted
      assertThat(orchestrator.isRunning(REDIS)).isFalse();
      assertThat(orchestrator.proxyExists(REDIS, "redis")).isFalse();
    }

    @Test
    @DisplayName("should be idempotent")
    void shouldBeIdempotent() {
      orchestrator.reset(REDIS);
      orchestrator.reset(REDIS); // Reset again

      // No exception
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandlingTests {

    @Test
    @DisplayName("should fail gracefully if Toxiproxy fails to start")
    void shouldHandleStartupFailure() {
      ToxiproxyConfig badConfig =
          new ToxiproxyConfig(
              "http://localhost:9999", // Invalid URL
              100,
              100,
              2000,
              5000,
              5000);

      ToxiproxyOrchestrator badOrchestrator = new ToxiproxyOrchestrator(badConfig);

      assertThatThrownBy(() -> badOrchestrator.start(REDIS)).hasMessageContaining("9999");
    }

    @Test
    @DisplayName("should handle toxic operations on non-existent proxy")
    void shouldHandleNonExistentProxy() {
      orchestrator.start(REDIS);

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      assertThatThrownBy(() -> orchestrator.addToxic(REDIS, "nonexistent", toxic))
          .hasMessageContaining("proxy")
          .hasMessageContaining("not found");
    }
  }

  @Nested
  @DisplayName("Configuration")
  class ConfigurationTests {

    @Test
    @DisplayName("should use custom configuration")
    void shouldUseCustomConfiguration() {
      ToxiproxyConfig customConfig =
          new ToxiproxyConfig(
              "http://localhost:8474",
              5000, // 5s startup timeout
              50, // 50ms poll interval
              1000, // 1s proxy ready timeout
              3000, // 3s connection timeout
              3000); // 3s read timeout

      ToxiproxyOrchestrator customOrchestrator = new ToxiproxyOrchestrator(customConfig);

      customOrchestrator.start(REDIS);
      assertThat(customOrchestrator.isRunning(REDIS)).isTrue();

      customOrchestrator.reset(REDIS);
    }

    @Test
    @DisplayName("should fail on null config")
    void shouldFailOnNullConfig() {
      assertThatThrownBy(() -> new ToxiproxyOrchestrator(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config");
    }
  }
}
