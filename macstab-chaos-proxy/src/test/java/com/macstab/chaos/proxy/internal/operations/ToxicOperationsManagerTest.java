/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycleManager;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;
import com.macstab.chaos.proxy.internal.operations.toxic.*;

/**
 * Comprehensive tests for ToxicOperationsManager.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("ToxicOperationsManager")
class ToxicOperationsManagerTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4").withExposedPorts(6379);

  private final ToxiproxyConfig config = ToxiproxyConfig.defaults();
  private final ToxiproxyLifecycleManager lifecycle = new ToxiproxyLifecycleManager(config);
  private final ProxyOperationsManager proxyOps = new ProxyOperationsManager(config);
  private final ToxicOperationsManager toxicOps = new ToxicOperationsManager(config);

  @AfterEach
  void cleanup() {
    lifecycle.reset(REDIS);
  }

  @Nested
  @DisplayName("addToxic() - LatencyToxic")
  class LatencyToxicTests {

    @Test
    @DisplayName("should add latency toxic successfully")
    void shouldAddLatencyToxic() {
      // Given
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      LatencyToxic toxic =
          LatencyToxic.builder().name("latency").latencyMs(500).jitterMs(0).build();

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(REDIS, "redis", toxic));
    }

    @Test
    @DisplayName("should add latency with jitter")
    void shouldAddLatencyWithJitter() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      LatencyToxic toxic =
          LatencyToxic.builder().name("latency-jitter").latencyMs(300).jitterMs(100).build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(REDIS, "redis", toxic));
    }
  }

  @Nested
  @DisplayName("addToxic() - TimeoutToxic")
  class TimeoutToxicTests {

    @Test
    @DisplayName("should add timeout toxic successfully")
    void shouldAddTimeoutToxic() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      TimeoutToxic toxic =
          TimeoutToxic.builder().name("timeout").timeoutMs(5000).toxicity(0.5).build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(REDIS, "redis", toxic));
    }

    @Test
    @DisplayName("should add instant timeout (0ms)")
    void shouldAddInstantTimeout() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      TimeoutToxic toxic =
          TimeoutToxic.builder()
              .name("instant-timeout")
              .timeoutMs(0) // Instant close
              .toxicity(1.0) // 100% of connections
              .build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(REDIS, "redis", toxic));
    }
  }

  @Nested
  @DisplayName("addToxic() - BandwidthToxic")
  class BandwidthToxicTests {

    @Test
    @DisplayName("should add bandwidth toxic successfully")
    void shouldAddBandwidthToxic() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      BandwidthToxic toxic =
          BandwidthToxic.builder()
              .name("bandwidth")
              .rateKBps(100) // 100 KB/s limit
              .build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(REDIS, "redis", toxic));
    }
  }

  @Nested
  @DisplayName("addToxic() - SlowCloseToxic")
  class SlowCloseToxicTests {

    @Test
    @DisplayName("should add slow close toxic successfully")
    void shouldAddSlowCloseToxic() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      SlowCloseToxic toxic =
          SlowCloseToxic.builder()
              .name("slow-close")
              .delayMs(5000) // 5 second close delay
              .build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(REDIS, "redis", toxic));
    }
  }

  @Nested
  @DisplayName("addToxic() - LimitDataToxic")
  class LimitDataToxicTests {

    @Test
    @DisplayName("should add limit data toxic successfully")
    void shouldAddLimitDataToxic() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      LimitDataToxic toxic =
          LimitDataToxic.builder()
              .name("limit-data")
              .bytes(1024) // Only 1KB transmitted
              .build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(REDIS, "redis", toxic));
    }
  }

  @Nested
  @DisplayName("removeToxic()")
  class RemoveToxicTests {

    @Test
    @DisplayName("should remove existing toxic")
    void shouldRemoveExistingToxic() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(500).build();

      toxicOps.addToxic(REDIS, "redis", toxic);

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.removeToxic(REDIS, "redis", "latency"));
    }

    @Test
    @DisplayName("should be idempotent (removing non-existent toxic)")
    void shouldBeIdempotent() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.removeToxic(REDIS, "redis", "nonexistent"));
    }
  }

  @Nested
  @DisplayName("removeAllToxics()")
  class RemoveAllToxicsTests {

    @Test
    @DisplayName("should remove all toxics from proxy")
    void shouldRemoveAllToxics() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      // Add multiple toxics
      toxicOps.addToxic(
          REDIS, "redis", LatencyToxic.builder().name("latency").latencyMs(100).build());
      toxicOps.addToxic(
          REDIS,
          "redis",
          TimeoutToxic.builder().name("timeout").timeoutMs(5000).toxicity(0.1).build());

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.removeAllToxics(REDIS, "redis"));
    }

    @Test
    @DisplayName("should be idempotent")
    void shouldBeIdempotent() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      toxicOps.removeAllToxics(REDIS, "redis");

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.removeAllToxics(REDIS, "redis"));
    }
  }

  @Nested
  @DisplayName("Toxic Lifecycle Scenarios")
  class LifecycleScenarios {

    @Test
    @DisplayName("should support add → remove → add cycle")
    void shouldSupportAddRemoveAddCycle() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(500).build();

      toxicOps.addToxic(REDIS, "redis", toxic);
      toxicOps.removeToxic(REDIS, "redis", "latency");
      toxicOps.addToxic(REDIS, "redis", toxic); // Add again

      // No exception = success
    }

    @Test
    @DisplayName("should handle multiple toxic types simultaneously")
    void shouldHandleMultipleToxicTypes() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      // Add all toxic types
      toxicOps.addToxic(
          REDIS, "redis", LatencyToxic.builder().name("latency").latencyMs(100).build());
      toxicOps.addToxic(
          REDIS,
          "redis",
          TimeoutToxic.builder().name("timeout").timeoutMs(5000).toxicity(0.1).build());
      toxicOps.addToxic(
          REDIS, "redis", BandwidthToxic.builder().name("bandwidth").rateKBps(50).build());
      toxicOps.addToxic(
          REDIS, "redis", SlowCloseToxic.builder().name("slow-close").delayMs(2000).build());
      toxicOps.addToxic(
          REDIS, "redis", LimitDataToxic.builder().name("limit-data").bytes(1024).build());

      // Remove all
      assertThatNoException().isThrownBy(() -> toxicOps.removeAllToxics(REDIS, "redis"));
    }

    @Test
    @DisplayName("should handle rapid add/remove cycles")
    void shouldHandleRapidCycles() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      proxyOps.createProxy(REDIS, proxyConfig);

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      for (int i = 0; i < 10; i++) {
        toxicOps.addToxic(REDIS, "redis", toxic);
        toxicOps.removeToxic(REDIS, "redis", "latency");
      }

      // No exception = success
    }
  }

  @Nested
  @DisplayName("Validation")
  class ValidationTests {

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      assertThatThrownBy(() -> toxicOps.addToxic(null, "redis", toxic))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null proxy name")
    void shouldFailOnNullProxyName() {
      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      assertThatThrownBy(() -> toxicOps.addToxic(REDIS, null, toxic))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null toxic config")
    void shouldFailOnNullToxicConfig() {
      assertThatThrownBy(() -> toxicOps.addToxic(REDIS, "redis", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail if proxy does not exist")
    void shouldFailIfProxyDoesNotExist() {
      lifecycle.start(REDIS);

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      assertThatThrownBy(() -> toxicOps.addToxic(REDIS, "nonexistent", toxic))
          .hasMessageContaining("proxy")
          .hasMessageContaining("not found");
    }
  }

  @Nested
  @DisplayName("Configuration")
  class ConfigurationTests {

    @Test
    @DisplayName("should fail on null config")
    void shouldFailOnNullConfig() {
      assertThatThrownBy(() -> new ToxicOperationsManager(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config");
    }
  }
}
