/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.toxiproxy.config.ProxyConfiguration;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycleManager;
import com.macstab.chaos.toxiproxy.network.NetworkRedirectManager;

/**
 * Integration test proving that multiple independent modules can share one Toxiproxy process
 * on the same container without interfering with each other's proxy configurations.
 *
 * <p>Simulates the real-world scenario: proxy module creates "redis" proxy, connection module
 * creates "conn_db_5432" proxy. Each module's surgical reset removes only its own proxy.
 * Shutdown kills everything.
 *
 * <p>This is a single sequential test method because the scenario has strict ordering
 * requirements that cannot be expressed via JUnit 5's {@code @Order} across nested classes.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("Cross-Module Toxiproxy Isolation")
class CrossModuleIsolationTest {

  @Container
  private static final GenericContainer<?> CONTAINER =
      new GenericContainer<>("redis:7.4")
          .withExposedPorts(6379)
          .withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));

  @Test
  @DisplayName("Surgical reset isolates modules — proxy reset does not affect connection's proxies")
  void surgicalResetIsolatesModules() throws IOException {
    final ToxiproxyConfig config = ToxiproxyConfig.defaults();
    final ToxiproxyLifecycleManager lifecycle = new ToxiproxyLifecycleManager(config);
    final ToxiproxyApiClient api = new ToxiproxyApiClientImpl(config.apiUrl());
    final NetworkRedirectManager network = new NetworkRedirectManager();
    final ContainerContext ctx = ContainerContext.of(CONTAINER);

    // --- Start Toxiproxy ---
    lifecycle.ensureRunning(ctx);
    assertThat(lifecycle.isHealthy(ctx)).isTrue();

    // --- Step 1: Both modules create proxies ---
    final ProxyConfiguration redisConfig =
        new ProxyConfiguration("redis", 6379, 16379, CONTAINER.getHost());
    final ProxyConfiguration dbConfig =
        new ProxyConfiguration("conn_db_5432", 5432, 15432, CONTAINER.getHost());

    api.createProxy(ctx, redisConfig);
    network.setupRedirect(ctx, 6379, 16379);

    api.createProxy(ctx, dbConfig);
    network.setupRedirect(ctx, 5432, 15432);

    // Both proxies coexist
    assertThat(api.proxyExists(ctx, "redis")).isTrue();
    assertThat(api.proxyExists(ctx, "conn_db_5432")).isTrue();

    // --- Step 2: Proxy module resets (surgical — only removes "redis") ---
    api.deleteProxy(ctx, "redis");
    network.removeRedirect(ctx, redisConfig.servicePort(), redisConfig.proxyPort());

    // Proxy module's proxy gone
    assertThat(api.proxyExists(ctx, "redis")).isFalse();
    // Connection module's proxy SURVIVES
    assertThat(api.proxyExists(ctx, "conn_db_5432")).isTrue();
    // Toxiproxy still running
    assertThat(lifecycle.isHealthy(ctx)).isTrue();

    // --- Step 3: Connection module resets (surgical — only removes "conn_db_5432") ---
    api.deleteProxy(ctx, "conn_db_5432");
    network.removeRedirect(ctx, dbConfig.servicePort(), dbConfig.proxyPort());

    // Both gone now
    assertThat(api.proxyExists(ctx, "conn_db_5432")).isFalse();
    // Toxiproxy STILL running after both modules reset
    assertThat(lifecycle.isHealthy(ctx)).isTrue();

    // --- Cleanup ---
    lifecycle.shutdown(ctx);
  }

  @Test
  @DisplayName("Shutdown kills Toxiproxy — ALL proxies from ALL modules destroyed")
  void shutdownDestroysEverything() throws IOException {
    final ToxiproxyConfig config = ToxiproxyConfig.defaults();
    final ToxiproxyLifecycleManager lifecycle = new ToxiproxyLifecycleManager(config);
    final ToxiproxyApiClient api = new ToxiproxyApiClientImpl(config.apiUrl());
    final NetworkRedirectManager network = new NetworkRedirectManager();
    final ContainerContext ctx = ContainerContext.of(CONTAINER);

    // Start + create proxies for both modules
    lifecycle.ensureRunning(ctx);
    api.createProxy(ctx, new ProxyConfiguration("redis", 6379, 16379, CONTAINER.getHost()));
    api.createProxy(ctx, new ProxyConfiguration("conn_db_5432", 5432, 15432, CONTAINER.getHost()));
    network.setupRedirect(ctx, 6379, 16379);
    network.setupRedirect(ctx, 5432, 15432);

    assertThat(api.proxyExists(ctx, "redis")).isTrue();
    assertThat(api.proxyExists(ctx, "conn_db_5432")).isTrue();

    // --- Shutdown: nuclear ---
    lifecycle.shutdown(ctx);

    // Toxiproxy dead — both modules' proxies gone
    assertThat(lifecycle.isHealthy(ctx)).isFalse();
  }
}
