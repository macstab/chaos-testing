/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.toxiproxy;

import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.command.network.NetworkCommandBuilder;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;

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
  private static final String TOXIPROXY_BINARY = "toxiproxy-server";
  private static final int STARTUP_TIMEOUT_MS = 10000;
  private static final int POLL_INTERVAL_MS = 100;
  private static final int PROXY_READY_TIMEOUT_MS = 2000;

  private static final String CURL_POST_JSON =
      "curl -s -X POST %s -H 'Content-Type: application/json'";

  private final ToxiproxyInstaller installer = new ToxiproxyInstaller();

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
    ensureToxiproxyRunning(container);

    try {
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
      ensureToxiproxyRunning(container);

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
      stopToxiproxy(platform, shell, container);

      log.info("Reset proxy chaos (stopped Toxiproxy, removed port redirects)");

    } catch (final Exception e) {
      log.warn("Failed to fully reset proxy chaos", e);
    }
  }

  // ==================== Private Helper Methods ====================

  /**
   * Check proxy status (exists in API, listening on port).
   */
  private ProxyStatus checkProxyStatus(
      final GenericContainer<?> container, final Shell shell, final ProxyConfiguration config)
      throws Exception {

    final boolean existsInApi = proxyExistsInApi(container, shell, config.getProxyName());
    final boolean listening = proxyListening(container, shell, config.getProxyPort());

    return new ProxyStatus(existsInApi, listening);
  }

  /**
   * Check if proxy exists in Toxiproxy API.
   */
  private boolean proxyExistsInApi(
      final GenericContainer<?> container, final Shell shell, final String proxyName)
      throws Exception {

    final ExecResult result =
        shell.exec(
            container,
            String.format("curl -s -f %s/proxies/%s 2>&1", TOXIPROXY_API, proxyName));

    return result.getExitCode() == 0;
  }

  /**
   * Check if proxy port is listening.
   */
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

  /**
   * Recreate broken proxy (exists in API but port not listening).
   */
  private void recreateBrokenProxy(
      final GenericContainer<?> container,
      final Shell shell,
      final ProxyConfiguration config)
      throws Exception {

    log.warn(
        "Proxy '{}' exists in API but port {} not listening - recreating",
        config.getProxyName(),
        config.getProxyPort());

    shell.exec(
        container,
        String.format(
            "curl -s -X DELETE %s/proxies/%s 2>&1", TOXIPROXY_API, config.getProxyName()));
  }

  /**
   * Create new proxy in Toxiproxy API.
   */
  private void createNewProxy(
      final GenericContainer<?> container,
      final Platform platform,
      final Shell shell,
      final ProxyConfiguration config)
      throws Exception {

    final String createUrl = String.format("%s/proxies", TOXIPROXY_API);
    final String createCmd =
        String.format(
            CURL_POST_JSON
                + " -d '{\"name\":\"%s\",\"listen\":\"0.0.0.0:%d\","
                + "\"upstream\":\"localhost:%d\",\"enabled\":true}' 2>&1",
            createUrl,
            config.getProxyName(),
            config.getProxyPort(),
            config.getServicePort());

    final ExecResult result = shell.exec(container, createCmd);

    if (result.getExitCode() != 0) {
      throw new ChaosOperationFailedException("Failed to create proxy: " + result.getStderr());
    }
  }

  /**
   * Setup iptables port redirect.
   */
  private void setupPortRedirect(
      final GenericContainer<?> container,
      final Platform platform,
      final Shell shell,
      final ProxyConfiguration config)
      throws Exception {

    final NetworkCommandBuilder network = platform.getNetworkCommandBuilder();
    final String redirectCmd =
        network.buildAddRedirectCommand(config.getServicePort(), config.getProxyPort());

    final ExecResult result = shell.exec(container, redirectCmd);

    if (result.getExitCode() != 0) {
      throw new ChaosOperationFailedException(
          "Failed to setup port redirect: " + result.getStderr());
    }
  }

  /**
   * Check if toxic exists on proxy.
   */
  private boolean toxicExists(
      final GenericContainer<?> container,
      final Shell shell,
      final String proxyName,
      final String toxicName)
      throws Exception {

    final ExecResult result =
        shell.exec(
            container,
            String.format(
                "curl -s %s/proxies/%s/toxics 2>&1 | grep -q '\"name\":\"%s\"'",
                TOXIPROXY_API, proxyName, toxicName));

    return result.getExitCode() == 0;
  }

  /**
   * Create toxic on proxy.
   */
  private void createToxic(
      final GenericContainer<?> container,
      final Shell shell,
      final String proxyName,
      final String toxicName,
      final String toxicType,
      final String attributes,
      final double toxicity)
      throws Exception {

    final String createCmd =
        String.format(
            CURL_POST_JSON
                + " -d '{\"name\":\"%s\",\"type\":\"%s\",\"attributes\":%s,\"toxicity\":%.2f}' 2>&1",
            String.format("%s/proxies/%s/toxics", TOXIPROXY_API, proxyName),
            toxicName,
            toxicType,
            attributes,
            toxicity);

    final ExecResult result = shell.exec(container, createCmd);

    if (result.getExitCode() != 0) {
      throw new ChaosOperationFailedException(
          String.format("Failed to add toxic '%s': %s", toxicName, result.getStderr()));
    }
  }

  /**
   * Clear all port redirects.
   */
  private void clearPortRedirects(
      final Platform platform, final Shell shell, final GenericContainer<?> container)
      throws Exception {

    final NetworkCommandBuilder network = platform.getNetworkCommandBuilder();
    final String clearCmd = network.buildClearRedirectsCommand();
    shell.exec(container, clearCmd);
  }

  /**
   * Stop Toxiproxy server.
   */
  private void stopToxiproxy(
      final Platform platform, final Shell shell, final GenericContainer<?> container)
      throws Exception {

    final var processBuilder = platform.getProcessCommandBuilder();
    final String killCmd = processBuilder.buildKillAllProcessesCommand(TOXIPROXY_BINARY);
    shell.exec(container, killCmd);
  }

  /**
   * Ensure Toxiproxy server is running.
   */
  private void ensureToxiproxyRunning(final GenericContainer<?> container) {
    if (isToxiproxyRunning(container)) {
      return;
    }

    installer.install(container);
    startToxiproxyServer(container);
    waitForApiReady(container);

    log.info("Started Toxiproxy server");
  }

  /**
   * Check if Toxiproxy API is responding.
   */
  private boolean isToxiproxyRunning(final GenericContainer<?> container) {
    try {
      final ExecResult check =
          container.execInContainer("curl", "-s", TOXIPROXY_API + "/proxies");
      return check.getExitCode() == 0;
    } catch (final Exception e) {
      return false;
    }
  }

  /**
   * Start Toxiproxy server in background.
   */
  private void startToxiproxyServer(final GenericContainer<?> container) {
    try {
      final Platform platform = PlatformDetector.detect(container);
      final Shell shell = platform.getDefaultShell();
      shell.exec(container, TOXIPROXY_BINARY + " -host 0.0.0.0 >/dev/null 2>&1 &");
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start Toxiproxy", e);
    }
  }

  /**
   * Wait for Toxiproxy API to become ready.
   */
  private void waitForApiReady(final GenericContainer<?> container) {
    final long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;

    while (System.currentTimeMillis() < deadline) {
      if (isToxiproxyRunning(container)) {
        return;
      }

      sleep(POLL_INTERVAL_MS);
    }

    throw new ChaosOperationFailedException(
        "Toxiproxy did not start within " + STARTUP_TIMEOUT_MS + "ms");
  }

  /**
   * Validate proxy port is ready.
   */
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
        "Proxy port " + proxyPort + " did not become ready within " + PROXY_READY_TIMEOUT_MS + "ms");
  }

  /**
   * Validate container is running.
   */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }

  /**
   * Validate toxicity is in valid range.
   */
  private void validateToxicity(final double toxicity) {
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  /**
   * Log proxy already ready.
   */
  private void logProxyAlreadyReady(final ProxyConfiguration config) {
    log.debug(
        "Proxy '{}' already configured and listening on port {}",
        config.getProxyName(),
        config.getProxyPort());
  }

  /**
   * Log proxy created.
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
   */
  private void handleProxyCreationError(final Exception e) {
    if (e instanceof ChaosOperationFailedException) {
      throw (ChaosOperationFailedException) e;
    }
    throw new ChaosOperationFailedException("Failed to create proxy", e);
  }

  /**
   * Handle toxic creation error.
   */
  private void handleToxicCreationError(final Exception e) {
    if (e instanceof ChaosOperationFailedException) {
      throw (ChaosOperationFailedException) e;
    }
    throw new ChaosOperationFailedException("Failed to add toxic", e);
  }

  /**
   * Sleep without checked exception.
   */
  private void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChaosOperationFailedException("Interrupted", e);
    }
  }

  // ==================== Inner Classes ====================

  /**
   * Proxy status (exists in API, listening on port).
   */
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
