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

/**
 * Comprehensive tests for ProxyOperationsManager.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("ProxyOperationsManager")
class ProxyOperationsManagerTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4").withExposedPorts(6379);

  private final ToxiproxyConfig config = ToxiproxyConfig.defaults();
  private final ToxiproxyLifecycleManager lifecycle = new ToxiproxyLifecycleManager(config);
  private final ProxyOperationsManager operations = new ProxyOperationsManager(config);

  @AfterEach
  void cleanup() {
    lifecycle.reset(REDIS);
  }

  @Nested
  @DisplayName("createProxy()")
  class CreateProxyTests {

    @Test
    @DisplayName("should create proxy successfully")
    void shouldCreateProxy() {
      // Given
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      // When
      String hostname = operations.createProxy(REDIS, proxyConfig);

      // Then
      assertThat(hostname).isNotNull().isNotEmpty();
      assertThat(operations.proxyExists(REDIS, "redis")).isTrue();
    }

    @Test
    @DisplayName("should return container hostname")
    void shouldReturnContainerHostname() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      String hostname = operations.createProxy(REDIS, proxyConfig);

      // Should NOT be localhost (must be container hostname for iptables to work)
      assertThat(hostname).isNotEqualTo("localhost");
      assertThat(hostname).matches("[a-f0-9]{12}"); // Docker container ID format
    }

    @Test
    @DisplayName("should be idempotent (creating same proxy twice)")
    void shouldBeIdempotent() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      String hostname1 = operations.createProxy(REDIS, proxyConfig);
      String hostname2 = operations.createProxy(REDIS, proxyConfig); // Create again

      assertThat(hostname1).isEqualTo(hostname2);
      assertThat(operations.proxyExists(REDIS, "redis")).isTrue();
    }

    @Test
    @DisplayName("should create multiple proxies on same container")
    void shouldCreateMultipleProxies() {
      lifecycle.start(REDIS);
      ProxyConfiguration redis1 = new ProxyConfiguration("redis-1", 6379, 16379);
      ProxyConfiguration redis2 = new ProxyConfiguration("redis-2", 6379, 17379);

      operations.createProxy(REDIS, redis1);
      operations.createProxy(REDIS, redis2);

      assertThat(operations.proxyExists(REDIS, "redis-1")).isTrue();
      assertThat(operations.proxyExists(REDIS, "redis-2")).isTrue();
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      assertThatThrownBy(() -> operations.createProxy(null, proxyConfig))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null proxy configuration")
    void shouldFailOnNullProxyConfig() {
      assertThatThrownBy(() -> operations.createProxy(REDIS, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail if Toxiproxy not running")
    void shouldFailIfToxiproxyNotRunning() {
      // Don't start Toxiproxy
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      assertThatThrownBy(() -> operations.createProxy(REDIS, proxyConfig))
          .hasMessageContaining("Toxiproxy")
          .hasMessageContaining("not running");
    }
  }

  @Nested
  @DisplayName("deleteProxy()")
  class DeleteProxyTests {

    @Test
    @DisplayName("should delete existing proxy")
    void shouldDeleteExistingProxy() {
      // Given
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      operations.createProxy(REDIS, proxyConfig);

      // When
      operations.deleteProxy(REDIS, "redis");

      // Then
      assertThat(operations.proxyExists(REDIS, "redis")).isFalse();
    }

    @Test
    @DisplayName("should be idempotent (deleting non-existent proxy)")
    void shouldBeIdempotent() {
      lifecycle.start(REDIS);

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> operations.deleteProxy(REDIS, "nonexistent"));
    }

    @Test
    @DisplayName("should delete one proxy without affecting others")
    void shouldDeleteOneProxyOnly() {
      lifecycle.start(REDIS);
      ProxyConfiguration redis1 = new ProxyConfiguration("redis-1", 6379, 16379);
      ProxyConfiguration redis2 = new ProxyConfiguration("redis-2", 6379, 17379);
      operations.createProxy(REDIS, redis1);
      operations.createProxy(REDIS, redis2);

      // Delete one
      operations.deleteProxy(REDIS, "redis-1");

      assertThat(operations.proxyExists(REDIS, "redis-1")).isFalse();
      assertThat(operations.proxyExists(REDIS, "redis-2")).isTrue(); // Other still exists
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> operations.deleteProxy(null, "redis"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null proxy name")
    void shouldFailOnNullProxyName() {
      assertThatThrownBy(() -> operations.deleteProxy(REDIS, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("proxyExists()")
  class ProxyExistsTests {

    @Test
    @DisplayName("should return false when proxy does not exist")
    void shouldReturnFalseWhenNotExists() {
      lifecycle.start(REDIS);

      assertThat(operations.proxyExists(REDIS, "nonexistent")).isFalse();
    }

    @Test
    @DisplayName("should return true when proxy exists")
    void shouldReturnTrueWhenExists() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      operations.createProxy(REDIS, proxyConfig);

      assertThat(operations.proxyExists(REDIS, "redis")).isTrue();
    }

    @Test
    @DisplayName("should return false after deletion")
    void shouldReturnFalseAfterDeletion() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);
      operations.createProxy(REDIS, proxyConfig);
      operations.deleteProxy(REDIS, "redis");

      assertThat(operations.proxyExists(REDIS, "redis")).isFalse();
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> operations.proxyExists(null, "redis"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null proxy name")
    void shouldFailOnNullProxyName() {
      assertThatThrownBy(() -> operations.proxyExists(REDIS, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("reset()")
  class ResetTests {

    @Test
    @DisplayName("should delete all proxies")
    void shouldDeleteAllProxies() {
      lifecycle.start(REDIS);
      ProxyConfiguration redis1 = new ProxyConfiguration("redis-1", 6379, 16379);
      ProxyConfiguration redis2 = new ProxyConfiguration("redis-2", 6379, 17379);
      operations.createProxy(REDIS, redis1);
      operations.createProxy(REDIS, redis2);

      // When
      operations.reset(REDIS);

      // Then
      assertThat(operations.proxyExists(REDIS, "redis-1")).isFalse();
      assertThat(operations.proxyExists(REDIS, "redis-2")).isFalse();
    }

    @Test
    @DisplayName("should be idempotent")
    void shouldBeIdempotent() {
      lifecycle.start(REDIS);
      operations.reset(REDIS);

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> operations.reset(REDIS));
    }

    @Test
    @DisplayName("should handle reset when no proxies exist")
    void shouldHandleResetWhenNoProxies() {
      lifecycle.start(REDIS);

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> operations.reset(REDIS));
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> operations.reset(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Proxy Lifecycle Scenarios")
  class LifecycleScenarios {

    @Test
    @DisplayName("should support full lifecycle (create → delete → create)")
    void shouldSupportFullLifecycle() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      // Create
      operations.createProxy(REDIS, proxyConfig);
      assertThat(operations.proxyExists(REDIS, "redis")).isTrue();

      // Delete
      operations.deleteProxy(REDIS, "redis");
      assertThat(operations.proxyExists(REDIS, "redis")).isFalse();

      // Create again
      operations.createProxy(REDIS, proxyConfig);
      assertThat(operations.proxyExists(REDIS, "redis")).isTrue();
    }

    @Test
    @DisplayName("should handle rapid create/delete cycles")
    void shouldHandleRapidCycles() {
      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      for (int i = 0; i < 5; i++) {
        operations.createProxy(REDIS, proxyConfig);
        operations.deleteProxy(REDIS, "redis");
      }

      assertThat(operations.proxyExists(REDIS, "redis")).isFalse();
    }

    @Test
    @DisplayName("should cleanup all proxies on reset")
    void shouldCleanupAllProxiesOnReset() {
      lifecycle.start(REDIS);

      // Create many proxies
      for (int i = 0; i < 10; i++) {
        ProxyConfiguration config = new ProxyConfiguration("proxy-" + i, 6379, 16379 + i);
        operations.createProxy(REDIS, config);
      }

      // Reset
      operations.reset(REDIS);

      // Verify all deleted
      for (int i = 0; i < 10; i++) {
        assertThat(operations.proxyExists(REDIS, "proxy-" + i)).isFalse();
      }
    }
  }

  @Nested
  @DisplayName("Configuration")
  class ConfigurationTests {

    @Test
    @DisplayName("should fail on null config")
    void shouldFailOnNullConfig() {
      assertThatThrownBy(() -> new ProxyOperationsManager(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config");
    }

    @Test
    @DisplayName("should use custom API URL from config")
    void shouldUseCustomApiUrl() {
      ToxiproxyConfig customConfig =
          new ToxiproxyConfig(
              "http://localhost:9999", // Custom URL
              10000,
              100,
              2000,
              5000,
              5000);

      ProxyOperationsManager manager = new ProxyOperationsManager(customConfig);

      lifecycle.start(REDIS);
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379);

      // Should fail (custom URL not reachable)
      assertThatThrownBy(() -> manager.createProxy(REDIS, proxyConfig))
          .hasMessageContaining("9999"); // Custom port in error
    }
  }
}
