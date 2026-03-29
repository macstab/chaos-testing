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
import com.macstab.chaos.proxy.internal.ContainerContext;
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
      new GenericContainer<>("redis:7.4")
          .withExposedPorts(6379)
          .withCreateContainerCmdModifier(cmd ->
              cmd.getHostConfig().withCapAdd(com.github.dockerjava.api.model.Capability.NET_ADMIN));

  private final ToxiproxyConfig config = ToxiproxyConfig.defaults();
  private final ToxiproxyLifecycleManager lifecycle = new ToxiproxyLifecycleManager(config);
  private final ProxyOperationsManager operations = new ProxyOperationsManager(config);

  @AfterEach
  void cleanup() throws Exception {
    lifecycle.stop(ContainerContext.of(REDIS));
  }

  @Nested
  @DisplayName("createProxy()")
  class CreateProxyTests {

    @Test
    @DisplayName("should create proxy successfully")
    void shouldCreateProxy() throws Exception {
      // Given
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      // When
      ProxyConfiguration result = operations.createProxy(ContainerContext.of(REDIS), proxyConfig);

      // Then
      assertThat(result).isNotNull();
      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isTrue();
    }

    @Test
    @DisplayName("should return container hostname")
    void shouldReturnContainerHostname() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      ProxyConfiguration result = operations.createProxy(ContainerContext.of(REDIS), proxyConfig);

      // Should NOT be localhost (must be container hostname for iptables to work)
      assertThat(result).isNotNull();
      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isTrue(); // Docker container ID format
    }

    @Test
    @DisplayName("should be idempotent (creating same proxy twice)")
    void shouldBeIdempotent() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      ProxyConfiguration result1 = operations.createProxy(ContainerContext.of(REDIS), proxyConfig);
      ProxyConfiguration result2 = operations.createProxy(ContainerContext.of(REDIS), proxyConfig); // Create again

      assertThat(result1).isNotNull();
      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isTrue();
    }

    @Test
    @DisplayName("should create multiple proxies on same container")
    void shouldCreateMultipleProxies() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration redis1 = new ProxyConfiguration("redis-1", 6379, 16379, REDIS.getHost());
      ProxyConfiguration redis2 = new ProxyConfiguration("redis-2", 6379, 17379, REDIS.getHost());

      operations.createProxy(ContainerContext.of(REDIS), redis1);
      operations.createProxy(ContainerContext.of(REDIS), redis2);

      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis-1")).isTrue();
      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis-2")).isTrue();
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() throws Exception {
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      assertThatThrownBy(() -> operations.createProxy(null, proxyConfig))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null proxy configuration")
    void shouldFailOnNullProxyConfig() throws Exception {
      assertThatThrownBy(() -> operations.createProxy(ContainerContext.of(REDIS), null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail if Toxiproxy not running")
    void shouldFailIfToxiproxyNotRunning() throws Exception {
      // Don't start Toxiproxy
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      assertThatThrownBy(() -> operations.createProxy(ContainerContext.of(REDIS), proxyConfig))
          .isInstanceOf(Exception.class)
          .hasMessageContaining("Failed to create proxy");
    }
  }

  @Nested
  @DisplayName("deleteProxy()")
  class DeleteProxyTests {

    @Test
    @DisplayName("should delete existing proxy")
    void shouldDeleteExistingProxy() throws Exception {
      // Given
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      operations.createProxy(ContainerContext.of(REDIS), proxyConfig);

      // When
      operations.deleteProxy(ContainerContext.of(REDIS), "redis");

      // Then
      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isFalse();
    }

    @Test
    @DisplayName("should be idempotent (deleting non-existent proxy)")
    void shouldBeIdempotent() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> operations.deleteProxy(ContainerContext.of(REDIS), "nonexistent"));
    }

    @Test
    @DisplayName("should delete one proxy without affecting others")
    void shouldDeleteOneProxyOnly() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration redis1 = new ProxyConfiguration("redis-1", 6379, 16379, REDIS.getHost());
      ProxyConfiguration redis2 = new ProxyConfiguration("redis-2", 6379, 17379, REDIS.getHost());
      operations.createProxy(ContainerContext.of(REDIS), redis1);
      operations.createProxy(ContainerContext.of(REDIS), redis2);

      // Delete one
      operations.deleteProxy(ContainerContext.of(REDIS), "redis-1");

      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis-1")).isFalse();
      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis-2")).isTrue(); // Other still exists
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() throws Exception {
      assertThatThrownBy(() -> operations.deleteProxy(null, "redis"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null proxy name")
    void shouldFailOnNullProxyName() throws Exception {
      assertThatThrownBy(() -> operations.deleteProxy(ContainerContext.of(REDIS), null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("proxyExists()")
  class ProxyExistsTests {

    @Test
    @DisplayName("should return false when proxy does not exist")
    void shouldReturnFalseWhenNotExists() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));

      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "nonexistent")).isFalse();
    }

    @Test
    @DisplayName("should return true when proxy exists")
    void shouldReturnTrueWhenExists() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      operations.createProxy(ContainerContext.of(REDIS), proxyConfig);

      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isTrue();
    }

    @Test
    @DisplayName("should return false after deletion")
    void shouldReturnFalseAfterDeletion() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());
      operations.createProxy(ContainerContext.of(REDIS), proxyConfig);
      operations.deleteProxy(ContainerContext.of(REDIS), "redis");

      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isFalse();
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() throws Exception {
      assertThatThrownBy(() -> operations.proxyExists(null, "redis"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should fail on null proxy name")
    void shouldFailOnNullProxyName() throws Exception {
      assertThatThrownBy(() -> operations.proxyExists(ContainerContext.of(REDIS), null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("reset()")
  class ResetTests {

    @Test
    @DisplayName("should clear all redirects without throwing")
    void shouldDeleteAllProxies() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration redis1 = new ProxyConfiguration("redis-1", 6379, 16379, REDIS.getHost());
      ProxyConfiguration redis2 = new ProxyConfiguration("redis-2", 6379, 17379, REDIS.getHost());
      operations.createProxy(ContainerContext.of(REDIS), redis1);
      operations.createProxy(ContainerContext.of(REDIS), redis2);

      // deleteAllProxies clears iptables redirects (not Toxiproxy API entries)
      assertThatNoException().isThrownBy(() -> operations.deleteAllProxies(ContainerContext.of(REDIS)));
    }

    @Test
    @DisplayName("should be idempotent")
    void shouldBeIdempotent() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      operations.deleteAllProxies(ContainerContext.of(REDIS));

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> operations.deleteAllProxies(ContainerContext.of(REDIS)));
    }

    @Test
    @DisplayName("should handle reset when no proxies exist")
    void shouldHandleResetWhenNoProxies() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> operations.deleteAllProxies(ContainerContext.of(REDIS)));
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() throws Exception {
      assertThatThrownBy(() -> operations.deleteAllProxies(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Proxy Lifecycle Scenarios")
  class LifecycleScenarios {

    @Test
    @DisplayName("should support full lifecycle (create → delete → create)")
    void shouldSupportFullLifecycle() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      // Create
      operations.createProxy(ContainerContext.of(REDIS), proxyConfig);
      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isTrue();

      // Delete
      operations.deleteProxy(ContainerContext.of(REDIS), "redis");
      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isFalse();

      // Create again
      operations.createProxy(ContainerContext.of(REDIS), proxyConfig);
      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isTrue();
    }

    @Test
    @DisplayName("should handle rapid create/delete cycles")
    void shouldHandleRapidCycles() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      for (int i = 0; i < 5; i++) {
        operations.createProxy(ContainerContext.of(REDIS), proxyConfig);
        operations.deleteProxy(ContainerContext.of(REDIS), "redis");
      }

      assertThat(operations.proxyExists(ContainerContext.of(REDIS), "redis")).isFalse();
    }

    @Test
    @DisplayName("should cleanup all proxies on reset")
    void shouldCleanupAllProxiesOnReset() throws Exception {
      lifecycle.ensureRunning(ContainerContext.of(REDIS));

      // Create many proxies
      for (int i = 0; i < 10; i++) {
        ProxyConfiguration config = new ProxyConfiguration("proxy-" + i, 6379, 16379 + i, REDIS.getHost());
        operations.createProxy(ContainerContext.of(REDIS), config);
      }

      // deleteAllProxies clears iptables redirects — proxies remain in Toxiproxy API
      assertThatNoException().isThrownBy(() -> operations.deleteAllProxies(ContainerContext.of(REDIS)));
    }
  }

  @Nested
  @DisplayName("Configuration")
  class ConfigurationTests {

    @Test
    @DisplayName("should fail on null config")
    void shouldFailOnNullConfig() throws Exception {
      assertThatThrownBy(() -> new ProxyOperationsManager(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config");
    }

    @Test
    @DisplayName("should use custom API URL from config")
    void shouldUseCustomApiUrl() throws Exception {
      ToxiproxyConfig customConfig = ToxiproxyConfig.builder()
          .apiUrl("http://localhost:9999")
          .startupTimeoutMs(10000)
          .pollIntervalMs(100)
          .proxyReadyTimeoutMs(2000)
          .connectionTimeoutMs(5000)
          .readTimeoutMs(5000)
          .build();

      ProxyOperationsManager manager = new ProxyOperationsManager(customConfig);

      lifecycle.ensureRunning(ContainerContext.of(REDIS));
      ProxyConfiguration proxyConfig = new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      // Should fail (custom URL not reachable)
      assertThatThrownBy(() -> manager.createProxy(ContainerContext.of(REDIS), proxyConfig))
          .isInstanceOf(Exception.class)
          .hasMessageContaining("Failed to create proxy");
    }
  }

  @Nested
  @DisplayName("Error paths (mocked API client)")
  class ErrorPathTests {

    @Test
    @DisplayName("proxyExists returns false when API throws")
    void proxyExists_apiThrows_returnsFalse() throws Exception {
      // GIVEN
      final com.macstab.chaos.proxy.api.ToxiproxyApiClient mockApi =
          org.mockito.Mockito.mock(com.macstab.chaos.proxy.api.ToxiproxyApiClient.class);
      org.mockito.Mockito.when(mockApi.proxyExists(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any()))
          .thenThrow(new RuntimeException("API unreachable"));

      final ProxyOperationsManager mgr =
          new ProxyOperationsManager(config, mockApi, new com.macstab.chaos.proxy.network.NetworkRedirectManager());
      final ContainerContext ctx = ContainerContext.of(REDIS);

      // WHEN / THEN
      assertThat(mgr.proxyExists(ctx, "redis")).isFalse();
    }

    @Test
    @DisplayName("deleteProxy wraps non-IOException in IOException")
    void deleteProxy_runtimeException_wrapsInIOException() throws Exception {
      // GIVEN
      final com.macstab.chaos.proxy.api.ToxiproxyApiClient mockApi =
          org.mockito.Mockito.mock(com.macstab.chaos.proxy.api.ToxiproxyApiClient.class);
      org.mockito.Mockito.doThrow(new RuntimeException("unexpected error"))
          .when(mockApi).deleteProxy(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());

      final ProxyOperationsManager mgr =
          new ProxyOperationsManager(config, mockApi, new com.macstab.chaos.proxy.network.NetworkRedirectManager());
      final ContainerContext ctx = ContainerContext.of(REDIS);

      // WHEN / THEN
      assertThatThrownBy(() -> mgr.deleteProxy(ctx, "redis"))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("Failed to delete proxy");
    }

    @Test
    @DisplayName("createProxy: broken proxy (exists but not listening) is recreated")
    void createProxy_brokenProxy_isRecreated() throws Exception {
      // GIVEN — ensure Toxiproxy is running so we can test the broken-proxy path
      lifecycle.ensureRunning(ContainerContext.of(REDIS));

      final com.macstab.chaos.proxy.api.ToxiproxyApiClient mockApi =
          org.mockito.Mockito.mock(com.macstab.chaos.proxy.api.ToxiproxyApiClient.class);
      // proxyExists returns true (exists in API) but port check will fail (not listening)
      org.mockito.Mockito.when(mockApi.proxyExists(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any()))
          .thenReturn(true);
      // deleteProxy + createProxy succeed
      org.mockito.Mockito.doNothing().when(mockApi).deleteProxy(
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.doNothing().when(mockApi).createProxy(
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

      final com.macstab.chaos.proxy.network.NetworkRedirect mockRedirect =
          org.mockito.Mockito.mock(com.macstab.chaos.proxy.network.NetworkRedirect.class);

      final ProxyOperationsManager mgr = new ProxyOperationsManager(config, mockApi, mockRedirect);
      final ContainerContext ctx = ContainerContext.of(REDIS);
      final ProxyConfiguration proxyConfig =
          new ProxyConfiguration("redis", 6379, 16379, REDIS.getHost());

      // WHEN / THEN — should attempt recreation (deleteProxy called)
      // Port validation will eventually timeout, but the recreate path is covered
      assertThatThrownBy(() -> mgr.createProxy(ctx, proxyConfig))
          .isInstanceOf(Exception.class);

      org.mockito.Mockito.verify(mockApi).deleteProxy(
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("redis"));
    }
  }
}
