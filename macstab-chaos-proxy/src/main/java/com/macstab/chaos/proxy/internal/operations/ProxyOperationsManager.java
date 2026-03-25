/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations;

import java.io.IOException;
import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.api.ToxiproxyApiClient;
import com.macstab.chaos.proxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;
import com.macstab.chaos.proxy.network.NetworkRedirect;
import com.macstab.chaos.proxy.network.NetworkRedirectManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of proxy operations.
 *
 * <p>Manages TCP proxy lifecycle including creation, deletion, status checking, and network
 * redirection setup.
 *
 * <p>Caches platform detection per container for performance.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ProxyOperationsManager implements ProxyOperations {

  private final ToxiproxyConfig config;
  private final ToxiproxyApiClient apiClient;
  private final NetworkRedirect networkRedirect;

  // Platform caching
  private Platform cachedPlatform;
  private GenericContainer<?> cachedContainer;

  /**
   * Create proxy operations manager with configuration.
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
      final GenericContainer<?> container, final ProxyConfiguration config) throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(config, "config must not be null");

    validateContainerRunning(container);

    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();

      final ProxyStatus status = checkProxyStatus(container, shell, config);

      if (status.isReady()) {
        logProxyAlreadyReady(config);
        return config;
      }

      if (status.existsInApi() && !status.isListening()) {
        recreateBrokenProxy(container, shell, config);
      }

      createNewProxy(container, shell, config);
      setupPortRedirect(container, platform, shell, config);
      validateProxyReady(container, shell, config.getProxyPort());

      logProxyCreated(config);
      return config;

    } catch (final Exception e) {
      throw handleProxyCreationError(e);
    }
  }

  @Override
  public void deleteProxy(final GenericContainer<?> container, final String proxyName)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    validateContainerRunning(container);

    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();

      apiClient.deleteProxy(container, shell, proxyName);
      log.info("Deleted proxy '{}'", proxyName);

    } catch (final Exception e) {
      throw new IOException("Failed to delete proxy: " + proxyName, e);
    }
  }

  @Override
  public boolean proxyExists(final GenericContainer<?> container, final String proxyName) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    if (!container.isRunning()) {
      return false;
    }

    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();
      return apiClient.proxyExists(container, shell, proxyName);

    } catch (final Exception e) {
      log.trace("Failed to check proxy existence: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public void deleteAllProxies(final GenericContainer<?> container) throws IOException {
    Objects.requireNonNull(container, "container must not be null");

    validateContainerRunning(container);

    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();

      networkRedirect.clearAllRedirects(container, shell);
      log.info("Deleted all proxies and cleared redirects");

    } catch (final Exception e) {
      throw new IOException("Failed to delete all proxies", e);
    }
  }

  // ==================== Private Implementation ====================

  /**
   * Check proxy status (exists in API, listening on port).
   *
   * @param container target container
   * @param shell shell instance
   * @param config proxy configuration
   * @return proxy status
   * @throws Exception if check fails
   */
  private ProxyStatus checkProxyStatus(
      final GenericContainer<?> container, final Shell shell, final ProxyConfiguration config)
      throws Exception {

    final boolean existsInApi = apiClient.proxyExists(container, shell, config.getProxyName());
    final boolean listening = isProxyListening(container, shell, config.getProxyPort());

    return new ProxyStatus(existsInApi, listening);
  }

  /**
   * Check if proxy port is listening.
   *
   * @param container target container
   * @param shell shell instance
   * @param proxyPort proxy port number
   * @return true if listening, false otherwise
   */
  private boolean isProxyListening(
      final GenericContainer<?> container, final Shell shell, final int proxyPort) {

    try {
      final String portCheckCmd = shell.buildPortCheckCommand(proxyPort);
      final ExecResult result = shell.exec(container, portCheckCmd);
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      return false;
    }
  }

  /**
   * Recreate broken proxy (exists in API but port not listening).
   *
   * @param container target container
   * @param shell shell instance
   * @param config proxy configuration
   * @throws Exception if recreation fails
   */
  private void recreateBrokenProxy(
      final GenericContainer<?> container, final Shell shell, final ProxyConfiguration config)
      throws Exception {

    log.warn(
        "Proxy '{}' exists in API but port {} not listening - recreating",
        config.getProxyName(),
        config.getProxyPort());

    apiClient.deleteProxy(container, shell, config.getProxyName());
  }

  /**
   * Create new proxy in Toxiproxy API.
   *
   * @param container target container
   * @param shell shell instance
   * @param config proxy configuration
   * @throws Exception if creation fails
   */
  private void createNewProxy(
      final GenericContainer<?> container, final Shell shell, final ProxyConfiguration config)
      throws Exception {

    apiClient.createProxy(container, shell, config);
  }

  /**
   * Setup network port redirect.
   *
   * @param container target container
   * @param platform platform instance
   * @param shell shell instance
   * @param config proxy configuration
   * @throws Exception if setup fails
   */
  private void setupPortRedirect(
      final GenericContainer<?> container,
      final Platform platform,
      final Shell shell,
      final ProxyConfiguration config)
      throws Exception {

    networkRedirect.setupRedirect(container, shell, config.getServicePort(), config.getProxyPort());
  }

  /**
   * Validate proxy port is ready and listening.
   *
   * @param container target container
   * @param shell shell instance
   * @param proxyPort proxy port number
   * @throws ChaosOperationFailedException if timeout reached
   */
  private void validateProxyReady(
      final GenericContainer<?> container, final Shell shell, final int proxyPort) {

    final String checkCmd = shell.buildPortCheckCommand(proxyPort);
    final long deadline = System.currentTimeMillis() + config.proxyReadyTimeoutMs();

    while (System.currentTimeMillis() < deadline) {
      try {
        final ExecResult result = shell.exec(container, checkCmd);
        if (result.getExitCode() == 0) {
          return;
        }
      } catch (final Exception ignored) {
        // Continue polling
      }
      sleep(config.pollIntervalMs());
    }

    throw new ChaosOperationFailedException(
        "Proxy port "
            + proxyPort
            + " did not become ready within "
            + config.proxyReadyTimeoutMs()
            + "ms");
  }

  /**
   * Validate container is running.
   *
   * @param container target container
   * @throws IllegalStateException if container is not running
   */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }

  /**
   * Log proxy already ready.
   *
   * @param config proxy configuration
   */
  private void logProxyAlreadyReady(final ProxyConfiguration config) {
    log.debug(
        "Proxy '{}' already configured and listening on port {}",
        config.getProxyName(),
        config.getProxyPort());
  }

  /**
   * Log proxy created.
   *
   * @param config proxy configuration
   */
  private void logProxyCreated(final ProxyConfiguration config) {
    log.info(
        "Created proxy '{}': {}:{} → proxy:{} → localhost:{}",
        config.getProxyName(),
        config.getContainerHostname(),
        config.getServicePort(),
        config.getProxyPort(),
        config.getServicePort());
  }

  /**
   * Handle proxy creation error.
   *
   * @param e exception
   * @return IOException wrapping the error
   */
  private IOException handleProxyCreationError(final Exception e) {
    if (e instanceof ChaosOperationFailedException) {
      return new IOException("Failed to create proxy", e);
    }
    return new IOException("Failed to create proxy", e);
  }

  /**
   * Sleep for specified milliseconds, ignoring interrupts.
   *
   * @param millis milliseconds to sleep
   * @throws ChaosOperationFailedException if interrupted
   */
  private void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChaosOperationFailedException("Interrupted during proxy validation", e);
    }
  }

  /**
   * Get platform for container, using cache when available.
   *
   * @param container target container
   * @return platform instance
   */
  private Platform getPlatform(final GenericContainer<?> container) {
    if (cachedPlatform == null || cachedContainer != container) {
      cachedPlatform = PlatformDetector.detect(container);
      cachedContainer = container;
      log.trace("Platform detected and cached for container: {}", container.getDockerImageName());
    }
    return cachedPlatform;
  }

  // ==================== Inner Classes ====================

  /** Proxy status (exists in API, listening on port). */
  private static final class ProxyStatus {
    private final boolean existsInApi;
    private final boolean listening;

    ProxyStatus(final boolean existsInApi, final boolean listening) {
      this.existsInApi = existsInApi;
      this.listening = listening;
    }

    boolean existsInApi() {
      return existsInApi;
    }

    boolean isListening() {
      return listening;
    }

    boolean isReady() {
      return existsInApi && listening;
    }
  }
}
