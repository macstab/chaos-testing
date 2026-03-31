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

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.proxy.internal.operations.ProxyOperationsManager;
import com.macstab.chaos.proxy.internal.operations.ToxicOperationsManager;
import com.macstab.chaos.toxiproxy.config.ProxyConfiguration;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycleManager;
import com.macstab.chaos.toxiproxy.toxic.*;

/**
 * Comprehensive integration tests for ToxiproxyOrchestrator.
 *
 * <p>The orchestrator is a high-level facade over lifecycle, proxy, and toxic operations. Tests
 * cover: createProxy (auto-starts lifecycle), addToxic (raw parameters), reset.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("ToxiproxyOrchestrator")
class ToxiproxyOrchestratorTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4")
          .withExposedPorts(6379)
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));

  private final ToxiproxyConfig config = ToxiproxyConfig.defaults();
  private final ToxiproxyOrchestrator orchestrator = new ToxiproxyOrchestrator(config);
  private final ToxiproxyLifecycleManager lifecycle = new ToxiproxyLifecycleManager(config);
  private final ProxyOperationsManager proxyOps = new ProxyOperationsManager(config);
  private final ToxicOperationsManager toxicOps = new ToxicOperationsManager(config);

  @AfterEach
  void cleanup() {
    orchestrator.reset(REDIS);
  }

  @Nested
  @DisplayName("createProxy()")
  class CreateProxyTests {

    @Test
    @DisplayName("should auto-start lifecycle and create proxy")
    void shouldAutoStartAndCreateProxy() {
      ProxyConfiguration proxyConfig =
          new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      ProxyConfiguration result = orchestrator.createProxy(REDIS, proxyConfig);

      assertThat(result).isNotNull();
      assertThat(proxyOps.proxyExists(ContainerContext.of(REDIS), "redis")).isTrue();
      assertThat(lifecycle.isHealthy(ContainerContext.of(REDIS))).isTrue();
    }

    @Test
    @DisplayName("should create proxy without explicit lifecycle start")
    void shouldAutoStartOnProxyCreation() {
      ProxyConfiguration proxyConfig =
          new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      // No lifecycle.ensureRunning() call — orchestrator handles it
      ProxyConfiguration result = orchestrator.createProxy(REDIS, proxyConfig);

      assertThat(result).isNotNull();
      assertThat(proxyOps.proxyExists(ContainerContext.of(REDIS), "redis")).isTrue();
    }

    @Test
    @DisplayName("should create multiple proxies")
    void shouldCreateMultipleProxies() {
      ProxyConfiguration redis1 = new ProxyConfiguration("redis-1", 6379, 16379, REDIS.getHost());
      ProxyConfiguration redis2 = new ProxyConfiguration("redis-2", 6379, 17379, REDIS.getHost());

      orchestrator.createProxy(REDIS, redis1);
      orchestrator.createProxy(REDIS, redis2);

      assertThat(proxyOps.proxyExists(ContainerContext.of(REDIS), "redis-1")).isTrue();
      assertThat(proxyOps.proxyExists(ContainerContext.of(REDIS), "redis-2")).isTrue();
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, "localhost");

      assertThatThrownBy(() -> orchestrator.createProxy(null, proxyConfig))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should fail on null proxy config")
    void shouldFailOnNullProxyConfig() {
      assertThatThrownBy(() -> orchestrator.createProxy(REDIS, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyConfig");
    }
  }

  @Nested
  @DisplayName("addToxic()")
  class AddToxicTests {

    @Test
    @DisplayName("should add latency toxic via typed config")
    void shouldAddLatencyToxic() {
      final ProxyConfiguration proxyConfig =
          new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      orchestrator.createProxy(REDIS, proxyConfig);

      final LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();

      assertThatNoException().isThrownBy(() -> orchestrator.addToxic(REDIS, "redis", toxic));
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      final LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();
      assertThatThrownBy(() -> orchestrator.addToxic(null, "redis", toxic))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should fail on null proxy name")
    void shouldFailOnNullProxyName() {
      final LatencyToxic toxic = LatencyToxic.builder().name("latency").latencyMs(100).build();
      assertThatThrownBy(() -> orchestrator.addToxic(REDIS, null, toxic))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyName");
    }

    @Test
    @DisplayName("should fail on null toxic config")
    void shouldFailOnNullToxicConfig() {
      assertThatThrownBy(() -> orchestrator.addToxic(REDIS, "redis", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("toxicConfig");
    }
  }

  @Nested
  @DisplayName("reset()")
  class ResetTests {

    @Test
    @DisplayName("should reset all state")
    void shouldResetAllState() {
      ProxyConfiguration proxyConfig =
          new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      orchestrator.createProxy(REDIS, proxyConfig);

      orchestrator.reset(REDIS);

      assertThat(lifecycle.isHealthy(ContainerContext.of(REDIS))).isFalse();
      assertThat(proxyOps.proxyExists(ContainerContext.of(REDIS), "redis")).isFalse();
    }

    @Test
    @DisplayName("should be idempotent")
    void shouldBeIdempotent() {
      orchestrator.reset(REDIS);
      assertThatNoException().isThrownBy(() -> orchestrator.reset(REDIS));
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> orchestrator.reset(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandlingTests {

    @Test
    @DisplayName("should fail gracefully if Toxiproxy fails to start")
    void shouldHandleStartupFailure() {
      ToxiproxyConfig badConfig =
          ToxiproxyConfig.builder()
              .apiUrl("http://localhost:9999")
              .startupTimeoutMs(100)
              .pollIntervalMs(10)
              .build();

      ToxiproxyOrchestrator badOrchestrator = new ToxiproxyOrchestrator(badConfig);
      ProxyConfiguration proxyConfig =
          new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      assertThatThrownBy(() -> badOrchestrator.createProxy(REDIS, proxyConfig))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should fail on null config")
    void shouldFailOnNullConfig() {
      assertThatThrownBy(() -> new ToxiproxyOrchestrator(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config");
    }
  }

  @Nested
  @DisplayName("Default Constructor")
  class DefaultConstructorTests {

    @Test
    @DisplayName("should use default config when constructed with no-arg constructor")
    void shouldUseDefaultConfig() {
      ToxiproxyOrchestrator defaultOrchestrator = new ToxiproxyOrchestrator();
      assertThat(defaultOrchestrator).isNotNull();
    }
  }

  @Nested
  @DisplayName("deleteProxy()")
  class DeleteProxyTests {

    @Test
    @DisplayName("should delete proxy and allow recreation")
    void shouldDeleteProxy_andAllowRecreation() {
      // GIVEN — create proxy
      final ProxyConfiguration proxyConfig =
          new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      orchestrator.createProxy(REDIS, proxyConfig);
      assertThat(proxyOps.proxyExists(ContainerContext.of(REDIS), "redis")).isTrue();

      // WHEN — delete proxy
      orchestrator.deleteProxy(REDIS, "redis");

      // THEN — proxy gone from Toxiproxy API
      assertThat(proxyOps.proxyExists(ContainerContext.of(REDIS), "redis")).isFalse();

      // AND — can recreate cleanly
      assertThatNoException().isThrownBy(() -> orchestrator.createProxy(REDIS, proxyConfig));
    }

    @Test
    @DisplayName("should leave Toxiproxy running after deleting one proxy")
    void shouldLeaveLifecycleRunning_afterDelete() {
      // GIVEN
      final ProxyConfiguration proxyConfig =
          new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      orchestrator.createProxy(REDIS, proxyConfig);

      // WHEN
      orchestrator.deleteProxy(REDIS, "redis");

      // THEN — Toxiproxy process still alive
      assertThat(lifecycle.isHealthy(ContainerContext.of(REDIS))).isTrue();
    }

    @Test
    @DisplayName("should delete one proxy without affecting others")
    void shouldDeleteOneProxy_leavingOthersIntact() {
      // GIVEN — two proxies
      orchestrator.createProxy(
          REDIS, new ProxyConfiguration("redis-1", 6379, 16379, REDIS.getHost()));
      orchestrator.createProxy(
          REDIS, new ProxyConfiguration("redis-2", 6379, 17379, REDIS.getHost()));

      // WHEN — delete only redis-1
      orchestrator.deleteProxy(REDIS, "redis-1");

      // THEN — redis-2 still exists
      assertThat(proxyOps.proxyExists(ContainerContext.of(REDIS), "redis-1")).isFalse();
      assertThat(proxyOps.proxyExists(ContainerContext.of(REDIS), "redis-2")).isTrue();
    }

    @Test
    @DisplayName("should throw NullPointerException on null container")
    void shouldThrowNpe_onNullContainer() {
      assertThatThrownBy(() -> orchestrator.deleteProxy(null, "redis"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should throw NullPointerException on null proxyName")
    void shouldThrowNpe_onNullProxyName() {
      assertThatThrownBy(() -> orchestrator.deleteProxy(REDIS, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyName");
    }
  }
}
