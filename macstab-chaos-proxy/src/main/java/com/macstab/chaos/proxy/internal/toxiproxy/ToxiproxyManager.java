/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.toxiproxy;

import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.api.ToxiproxyApiClient;
import com.macstab.chaos.proxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle;
import com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycleManager;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;
import com.macstab.chaos.proxy.network.NetworkRedirect;
import com.macstab.chaos.proxy.network.NetworkRedirectManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Internal manager for Toxiproxy operations.
 *
 * <p><strong>INTERNAL USE ONLY</strong> - Implementation detail, not part of public API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyManager {

  private static final String TOXIPROXY_API = "http://localhost:8474";
  private static final int PROXY_READY_TIMEOUT_MS = 2000;

  private final ToxiproxyLifecycle lifecycle = new ToxiproxyLifecycleManager();
  private final ToxiproxyApiClient apiClient = new ToxiproxyApiClientImpl(TOXIPROXY_API);
  private final NetworkRedirect networkRedirect = new NetworkRedirectManager();

  /**
   * Create a proxy for a TCP service.
   *
   * @param container container
   * @param config proxy configuration
   * @return proxy configuration with container hostname
   */
  public ProxyConfiguration createProxy(
      final GenericContainer<?> container, final ProxyConfiguration config) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(config, "config must not be null");

    validateContainerRunning(container);
    
    try {
      lifecycle.ensureRunning(container);
      final Platform platform = PlatformDetector.detect(container);
      final Shell shell = platform.getDefaultShell();

      final ProxyStatus status = checkProxyStatus(container, shell, config);

      if (status.isReady()) {
        logProxyAlreadyReady(config);
        return config;
      }

      if (status.existsInApi() && !status.isListening()) {
        recreateBrokenProxy(container, shell, config);
      }

      createNewProxy(container, platform, shell, config);
      setupPortRedirect(container, platform, shell, config);
      validateProxyReady(container, config.getProxyPort());

      logProxyCreated(config);
      return config;

    } catch (final Exception e) {
      handleProxyCreationError(e);
      return null; // Unreachable
    }
  }

  /**
   * Add a toxic to a proxy.
   *
   * @param container container
   * @param proxyName proxy name
   * @param toxicName toxic name
   * @param toxicType toxic type
   * @param attributes toxic attributes (JSON)
   * @param toxicity probability (0.0-1.0)
   */
  public void addToxic(
      final GenericContainer<?> container,
      final String proxyName,
      final String toxicName,
      final String toxicType,
      final String attributes,
      final double toxicity) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");
    Objects.requireNonNull(toxicType, "toxicType must not be null");

    validateToxicity(toxicity);

    try {
      lifecycle.ensureRunning(container);

      final Platform platform = PlatformDetector.detect(container);
      final Shell shell = platform.getDefaultShell();

      if (toxicExists(container, shell, proxyName, toxicName)) {
        log.debug("Toxic '{}' already exists on proxy '{}', skipping", toxicName, proxyName);
        return;
      }

      createToxic(container, shell, proxyName, toxicName, toxicType, attributes, toxicity);

      log.info(
          "Added toxic '{}' to proxy '{}' (type={}, toxicity={})",
          toxicName,
          proxyName,
          toxicType,
          toxicity);

    } catch (final Exception e) {
      handleToxicCreationError(e);
    }
  }

  /**
   * Remove all toxics and stop Toxiproxy.
   *
   * @param container container
   */
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      final Platform platform = PlatformDetector.detect(container);
      final Shell shell = platform.getDefaultShell();

      clearPortRedirects(platform, shell, container);
      lifecycle.stop(container);

      log.info("Reset proxy chaos (stopped Toxiproxy, removed port redirects)");

    } catch (final Exception e) {
      log.warn("Failed to fully reset proxy chaos", e);
    }
  }

  // ==================== Private Helper Methods ====================

  /** Check proxy status (exists in API, listening on port). */
  private ProxyStatus checkProxyStatus(
      final GenericContainer<?> container, final Shell shell, final ProxyConfiguration config)
      throws Exception {

    final boolean existsInApi = proxyExistsInApi(container, shell, config.getProxyName());
    final boolean listening = proxyListening(container, shell, config.getProxyPort());

    return new ProxyStatus(existsInApi, listening);
  }

  /** Check if proxy exists in Toxiproxy API. */
  private boolean proxyExistsInApi(
      final GenericContainer<?> container, final Shell shell, final String proxyName)
      throws Exception {

    return apiClient.proxyExists(container, shell, proxyName);
  }

  /** Check if proxy port is listening. */
  private boolean proxyListening(
      final GenericContainer<?> container, final Shell shell, final int proxyPort) {

    try {
      final String portCheckCmd = shell.buildPortCheckCommand(proxyPort);
      final ExecResult result = shell.exec(container, portCheckCmd);
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      return false;
    }
  }

  /** Recreate broken proxy (exists in API but port not listening). */
  private void recreateBrokenProxy(
      final GenericContainer<?> container, final Shell shell, final ProxyConfiguration config)
      throws Exception {

    log.warn(
        "Proxy '{}' exists in API but port {} not listening - recreating",
        config.getProxyName(),
        config.getProxyPort());

    apiClient.deleteProxy(container, shell, config.getProxyName());
  }

  /** Create new proxy in Toxiproxy API. */
  private void createNewProxy(
      final GenericContainer<?> container,
      final Platform platform,
      final Shell shell,
      final ProxyConfiguration config)
      throws Exception {

    apiClient.createProxy(container, shell, config);
  }

  /** Setup iptables port redirect. */
  private void setupPortRedirect(
      final GenericContainer<?> container,
      final Platform platform,
      final Shell shell,
      final ProxyConfiguration config)
      throws Exception {

    networkRedirect.setupRedirect(container, shell, config.getServicePort(), config.getProxyPort());
  }

  /** Check if toxic exists on proxy. */
  private boolean toxicExists(
      final GenericContainer<?> container,
      final Shell shell,
      final String proxyName,
      final String toxicName)
      throws Exception {

    return apiClient.toxicExists(container, shell, proxyName, toxicName);
  }

  /** Create toxic on proxy. */
  private void createToxic(
      final GenericContainer<?> container,
      final Shell shell,
      final String proxyName,
      final String toxicName,
      final String toxicType,
      final String attributes,
      final double toxicity)
      throws Exception {

    apiClient.addToxic(container, shell, proxyName, toxicName, toxicType, attributes, toxicity);
  }

  /** Clear all port redirects. */
  private void clearPortRedirects(
      final Platform platform, final Shell shell, final GenericContainer<?> container)
      throws Exception {

    networkRedirect.clearAllRedirects(container, shell);
  }

  /** Validate proxy port is ready. */
  private void validateProxyReady(final GenericContainer<?> container, final int proxyPort)
      throws Exception {

    final Platform platform = PlatformDetector.detect(container);
    final Shell shell = platform.getDefaultShell();
    final String checkCmd = shell.buildPortCheckCommand(proxyPort);

    final long deadline = System.currentTimeMillis() + PROXY_READY_TIMEOUT_MS;

    while (System.currentTimeMillis() < deadline) {
      try {
        final ExecResult result = shell.exec(container, checkCmd);
        if (result.getExitCode() == 0) {
          return;
        }
      } catch (final Exception ignored) {
        // Continue
      }
      sleep(50);
    }

    throw new ChaosOperationFailedException(
        "Proxy port "
            + proxyPort
            + " did not become ready within "
            + PROXY_READY_TIMEOUT_MS
            + "ms");
  }

  /** Validate container is running. */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }

  /** Validate toxicity is in valid range. */
  private void validateToxicity(final double toxicity) {
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  /** Log proxy already ready. */
  private void logProxyAlreadyReady(final ProxyConfiguration config) {
    log.debug(
        "Proxy '{}' already configured and listening on port {}",
        config.getProxyName(),
        config.getProxyPort());
  }

  /** Log proxy created. */
  private void logProxyCreated(final ProxyConfiguration config) {
    log.info(
        "Created proxy '{}': {}:{} → proxy:{} → localhost:{}",
        config.getProxyName(),
        config.getContainerHostname(),
        config.getServicePort(),
        config.getProxyPort(),
        config.getServicePort());
  }

  /** Handle proxy creation error. */
  private void handleProxyCreationError(final Exception e) {
    if (e instanceof ChaosOperationFailedException) {
      throw (ChaosOperationFailedException) e;
    }
    throw new ChaosOperationFailedException("Failed to create proxy", e);
  }

  /** Handle toxic creation error. */
  private void handleToxicCreationError(final Exception e) {
    if (e instanceof ChaosOperationFailedException) {
      throw (ChaosOperationFailedException) e;
    }
    throw new ChaosOperationFailedException("Failed to add toxic", e);
  }

  /** Sleep without checked exception. */
  private void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChaosOperationFailedException("Interrupted", e);
    }
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
