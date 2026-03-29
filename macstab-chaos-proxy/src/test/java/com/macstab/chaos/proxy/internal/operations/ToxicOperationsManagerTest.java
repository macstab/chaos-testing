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

import com.github.dockerjava.api.model.Capability;

import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.ContainerContext;
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
      new GenericContainer<>("redis:7.4")
          .withExposedPorts(6379)
          .withCreateContainerCmdModifier(cmd ->
              cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));

  private final ToxiproxyConfig config = ToxiproxyConfig.defaults();
  private final ToxiproxyLifecycleManager lifecycle = new ToxiproxyLifecycleManager(config);
  private final ProxyOperationsManager proxyOps = new ProxyOperationsManager(config);
  private final ToxicOperationsManager toxicOps = new ToxicOperationsManager(config);

  @AfterEach
  void cleanup() throws Exception {
    lifecycle.stop(ContainerContext.of(REDIS));
  }

  @Nested
  @DisplayName("addToxic() - LatencyToxic")
  class LatencyToxicTests {

    @Test
    @DisplayName("should add latency toxic successfully")
    void shouldLatencyToxic() throws Exception {
      // Given
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      LatencyToxic toxic =
          LatencyToxic.builder().name("latency").latencyMs(500).jitterMs(0).build();

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic));
    }

    @Test
    @DisplayName("should add latency with jitter")
    void shouldLatencyWithJitter() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      LatencyToxic toxic =
          LatencyToxic.builder().name("latency-jitter").latencyMs(300).jitterMs(100).build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic));
    }
  }

  @Nested
  @DisplayName("addToxic() - TimeoutToxic")
  class TimeoutToxicTests {

    @Test
    @DisplayName("should add timeout toxic successfully")
    void shouldTimeoutToxic() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      TimeoutToxic toxic =
          TimeoutToxic.builder().name("timeout").timeoutMs(5000).toxicity(0.5).build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic));
    }

    @Test
    @DisplayName("should add instant timeout (0ms)")
    void shouldInstantTimeout() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      TimeoutToxic toxic =
          TimeoutToxic.builder()
              .name("instant-timeout")
              .timeoutMs(0) // Instant close
              .toxicity(1.0) // 100% of connections
              .build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic));
    }
  }

  @Nested
  @DisplayName("addToxic() - BandwidthToxic")
  class BandwidthToxicTests {

    @Test
    @DisplayName("should add bandwidth toxic successfully")
    void shouldBandwidthToxic() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      BandwidthToxic toxic =
          BandwidthToxic.builder()
              .name("bandwidth")
              .rateKbps(100) // 100 KB/s limit
              .build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic));
    }
  }

  @Nested
  @DisplayName("addToxic() - SlowCloseToxic")
  class SlowCloseToxicTests {

    @Test
    @DisplayName("should add slow close toxic successfully")
    void shouldSlowCloseToxic() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      SlowCloseToxic toxic =
          SlowCloseToxic.builder()
              .name("slow-close")
              .delayMs(5000) // 5 second close delay
              .build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic));
    }
  }

  @Nested
  @DisplayName("addToxic() - LimitDataToxic")
  class LimitDataToxicTests {

    @Test
    @DisplayName("should add limit data toxic successfully")
    void shouldLimitDataToxic() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      LimitDataToxic toxic =
          LimitDataToxic.builder()
              .name("limit-data")
              .bytes(1024) // Only 1KB transmitted
              .build();

      assertThatNoException().isThrownBy(() -> toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic));
    }
  }

  @Nested
  @DisplayName("removeToxic()")
  class RemoveToxicTests {

    @Test
    @DisplayName("should remove existing toxic")
    void shouldRemoveExistingToxic() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(500).build();

      toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic);

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.removeToxic(ContainerContext.of(REDIS), "redis", "latency"));
    }

    @Test
    @DisplayName("should be idempotent (removing non-existent toxic)")
    void shouldBeIdempotent() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.removeToxic(ContainerContext.of(REDIS), "redis", "nonexistent"));
    }
  }

  @Nested
  @DisplayName("removeAllToxics()")
  class RemoveAllToxicsTests {

    @Test
    @DisplayName("should remove all toxics from proxy")
    void shouldRemoveAllToxics() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      // Add multiple toxics
      toxicOps.addToxic(
          ContainerContext.of(REDIS), "redis", LatencyToxic.builder().name("latency").latencyMs(100).build());
      toxicOps.addToxic(
          ContainerContext.of(REDIS),
          "redis",
          TimeoutToxic.builder().name("timeout").timeoutMs(5000).toxicity(0.1).build());

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.removeAllToxics(ContainerContext.of(REDIS), "redis"));
    }

    @Test
    @DisplayName("should be idempotent")
    void shouldBeIdempotent() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      toxicOps.removeAllToxics(ContainerContext.of(REDIS), "redis");

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> toxicOps.removeAllToxics(ContainerContext.of(REDIS), "redis"));
    }
  }

  @Nested
  @DisplayName("Toxic Lifecycle Scenarios")
  class LifecycleScenarios {

    @Test
    @DisplayName("should support add → remove → add cycle")
    void shouldSupportAddRemoveAddCycle() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(500).build();

      toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic);
      toxicOps.removeToxic(ContainerContext.of(REDIS), "redis", "latency");
      toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic); // Add again

      // No exception = success
    }

    @Test
    @DisplayName("should handle multiple toxic types simultaneously")
    void shouldHandleMultipleToxicTypes() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      // Add all toxic types
      toxicOps.addToxic(
          ContainerContext.of(REDIS), "redis", LatencyToxic.builder().name("latency").latencyMs(100).build());
      toxicOps.addToxic(
          ContainerContext.of(REDIS),
          "redis",
          TimeoutToxic.builder().name("timeout").timeoutMs(5000).toxicity(0.1).build());
      toxicOps.addToxic(
          ContainerContext.of(REDIS), "redis", BandwidthToxic.builder().name("bandwidth").rateKbps(50).build());
      toxicOps.addToxic(
          ContainerContext.of(REDIS), "redis", SlowCloseToxic.builder().name("slow-close").delayMs(2000).build());
      toxicOps.addToxic(
          ContainerContext.of(REDIS), "redis", LimitDataToxic.builder().name("limit-data").bytes(1024).build());

      // Remove all
      assertThatNoException().isThrownBy(() -> toxicOps.removeAllToxics(ContainerContext.of(REDIS), "redis"));
    }

    @Test
    @DisplayName("should handle rapid add/remove cycles")
    void shouldHandleRapidCycles() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      proxyOps.createProxy(ContainerContext.of(REDIS), proxyConfig);

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      for (int i = 0; i < 10; i++) {
        toxicOps.addToxic(ContainerContext.of(REDIS), "redis", toxic);
        toxicOps.removeToxic(ContainerContext.of(REDIS), "redis", "latency");
      }

      // No exception = success
    }
  }

  @Nested
  @DisplayName("Validation")
  class ValidationTests {

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() throws Exception {
      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      assertThatThrownBy(() -> toxicOps.addToxic(null, "redis", toxic))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null proxy name")
    void shouldFailOnNullProxyName() throws Exception {
      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      assertThatThrownBy(() -> toxicOps.addToxic(ContainerContext.of(REDIS), null, toxic))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null toxic config")
    void shouldFailOnNullToxicConfig() throws Exception {
      assertThatThrownBy(() -> toxicOps.addToxic(ContainerContext.of(REDIS), "redis", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on container not running")
    void shouldFailOnContainerNotRunning() throws Exception {
      @SuppressWarnings("resource")
      GenericContainer<?> stopped = new GenericContainer<>("alpine:latest");
      // Not started

      LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      // container is not running — ctx.container().isRunning() returns false
      // Use mocked ctx to bypass platform detection — isRunning() check is what matters
      final com.macstab.chaos.core.platform.Platform platform =
          org.mockito.Mockito.mock(com.macstab.chaos.core.platform.Platform.class);
      final com.macstab.chaos.core.shell.Shell shell =
          org.mockito.Mockito.mock(com.macstab.chaos.core.shell.Shell.class);
      final ContainerContext stoppedCtx = ContainerContext.of(stopped, platform, shell);
      assertThatThrownBy(() -> toxicOps.addToxic(stoppedCtx, "redis", toxic))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be running");
    }
  }

  @Nested
  @DisplayName("Configuration")
  class ConfigurationTests {

    @Test
    @DisplayName("should fail on null config")
    void shouldFailOnNullConfig() throws Exception {
      assertThatThrownBy(() -> new ToxicOperationsManager(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config");
    }
  }
}
