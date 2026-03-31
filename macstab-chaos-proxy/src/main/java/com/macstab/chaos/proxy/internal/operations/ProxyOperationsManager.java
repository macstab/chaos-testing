/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations;

import java.io.IOException;
import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.config.ProxyConfiguration;
import com.macstab.chaos.toxiproxy.network.NetworkRedirect;
import com.macstab.chaos.toxiproxy.network.NetworkRedirectManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of proxy CRUD operations.
 *
 * <p>Receives a pre-resolved {@link ContainerContext} on every call — no platform detection inside
 * this class.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ProxyOperationsManager implements ProxyOperations {

  private final ToxiproxyConfig config;
  private final ToxiproxyApiClient apiClient;
  private final NetworkRedirect networkRedirect;

  /**
   * Create proxy operations manager with default components.
   *
   * @param config Toxiproxy configuration
   */
  public ProxyOperationsManager(final ToxiproxyConfig config) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.apiClient = new ToxiproxyApiClientImpl(config.apiUrl());
    this.networkRedirect = new NetworkRedirectManager();
  }

  /**
   * Create proxy operations manager with custom components (for testing).
   *
   * @param config Toxiproxy configuration
   * @param apiClient API client instance
   * @param networkRedirect network redirect instance
   */
  public ProxyOperationsManager(
      final ToxiproxyConfig config,
      final ToxiproxyApiClient apiClient,
      final NetworkRedirect networkRedirect) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient must not be null");
    this.networkRedirect =
        Objects.requireNonNull(networkRedirect, "networkRedirect must not be null");
  }

  @Override
  public ProxyConfiguration createProxy(
      final ContainerContext ctx, final ProxyConfiguration proxyConfig) throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyConfig, "proxyConfig must not be null");

    if (!ctx.container().isRunning()) {
      throw new IllegalStateException("Container must be running");
    }

    try {
      final ProxyStatus status = checkProxyStatus(ctx, proxyConfig);

      if (status.isReady()) {
        log.debug(
            "Proxy '{}' already configured and listening on port {}",
            proxyConfig.getProxyName(),
            proxyConfig.getProxyPort());
        return proxyConfig;
      }

      if (status.existsInApi() && !status.listening()) {
        log.warn(
            "Proxy '{}' exists in API but port {} not listening — recreating",
            proxyConfig.getProxyName(),
            proxyConfig.getProxyPort());
        apiClient.deleteProxy(ctx, proxyConfig.getProxyName());
      }

      apiClient.createProxy(ctx, proxyConfig);
      networkRedirect.setupRedirect(ctx, proxyConfig.getServicePort(), proxyConfig.getProxyPort());
      validateProxyReady(ctx, proxyConfig.getProxyPort());

      log.info(
          "Created proxy '{}': {}:{} → proxy:{} → localhost:{}",
          proxyConfig.getProxyName(),
          proxyConfig.getContainerHostname(),
          proxyConfig.getServicePort(),
          proxyConfig.getProxyPort(),
          proxyConfig.getServicePort());

      return proxyConfig;

    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException("Failed to create proxy", e);
    }
  }

  @Override
  public void deleteProxy(final ContainerContext ctx, final String proxyName) throws IOException {
    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    if (!ctx.container().isRunning()) {
      throw new IllegalStateException("Container must be running");
    }

    try {
      apiClient.deleteProxy(ctx, proxyName);
      log.info("Deleted proxy '{}'", proxyName);
    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException("Failed to delete proxy: " + proxyName, e);
    }
  }

  @Override
  public boolean proxyExists(final ContainerContext ctx, final String proxyName) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    if (!ctx.container().isRunning()) {
      return false;
    }

    try {
      return apiClient.proxyExists(ctx, proxyName);
    } catch (final Exception e) {
      log.trace("Failed to check proxy existence: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public void deleteAllProxies(final ContainerContext ctx) throws IOException {
    Objects.requireNonNull(ctx, "ctx must not be null");

    if (!ctx.container().isRunning()) {
      throw new IllegalStateException("Container must be running");
    }

    try {
      networkRedirect.clearAllRedirects(ctx);
      log.info("Cleared all port redirects");
    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException("Failed to delete all proxies", e);
    }
  }

  // ==================== Private Helpers ====================

  /**
   * Check whether the proxy exists in the API and whether its port is listening.
   *
   * @param ctx resolved container context
   * @param proxyConfig proxy configuration
   * @return current proxy status
   * @throws Exception if API check fails
   */
  private ProxyStatus checkProxyStatus(
      final ContainerContext ctx, final ProxyConfiguration proxyConfig) throws Exception {

    final boolean existsInApi = apiClient.proxyExists(ctx, proxyConfig.getProxyName());
    final boolean listening = isPortListening(ctx, proxyConfig.getProxyPort());
    return new ProxyStatus(existsInApi, listening);
  }

  /**
   * Check if a port is currently listening inside the container.
   *
   * @param ctx resolved container context
   * @param port port to check
   * @return true if listening
   */
  private boolean isPortListening(final ContainerContext ctx, final int port) {
    try {
      final String portCheckCmd = ctx.shell().buildPortCheckCommand(port);
      final ExecResult result = ctx.shell().exec(ctx.container(), portCheckCmd);
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      return false;
    }
  }

  /**
   * Poll until the proxy port becomes ready or timeout is reached.
   *
   * @param ctx resolved container context
   * @param proxyPort port to wait for
   * @throws ChaosOperationFailedException if timeout reached
   */
  private void validateProxyReady(final ContainerContext ctx, final int proxyPort) {
    final String checkCmd = ctx.shell().buildPortCheckCommand(proxyPort);
    final long deadline = System.currentTimeMillis() + config.proxyReadyTimeoutMs();

    while (System.currentTimeMillis() < deadline) {
      try {
        final ExecResult result = ctx.shell().exec(ctx.container(), checkCmd);
        if (result.getExitCode() == 0) {
          return;
        }
      } catch (final Exception ignored) {
        // Continue polling
      }
      sleepOrThrow(config.pollIntervalMs());
    }

    throw new ChaosOperationFailedException(
        "Proxy port "
            + proxyPort
            + " did not become ready within "
            + config.proxyReadyTimeoutMs()
            + "ms");
  }

  /**
   * Sleep for the given duration. Restores interrupt flag and throws if interrupted.
   *
   * @param millis milliseconds to sleep
   * @throws ChaosOperationFailedException if interrupted
   */
  private void sleepOrThrow(final int millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChaosOperationFailedException("Interrupted during proxy validation", e);
    }
  }

  // ==================== Inner Types ====================

  /**
   * Captures whether a proxy exists in Toxiproxy and whether its port is listening.
   *
   * @param existsInApi true if the proxy is registered in Toxiproxy's configuration
   * @param listening true if the proxy port is actively accepting connections
   */
  private record ProxyStatus(boolean existsInApi, boolean listening) {

    /** Returns true only when the proxy is both registered and accepting connections. */
    boolean isReady() {
      return existsInApi && listening;
    }
  }
}
