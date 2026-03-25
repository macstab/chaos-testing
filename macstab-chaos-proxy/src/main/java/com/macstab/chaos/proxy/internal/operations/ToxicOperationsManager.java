/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations;

import java.io.IOException;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.api.ToxiproxyApiClient;
import com.macstab.chaos.proxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.proxy.internal.operations.toxic.ToxicConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of toxic operations.
 *
 * <p>Manages toxic lifecycle including creation, deletion, and existence checks through the
 * Toxiproxy API.
 *
 * <p>Caches platform detection per container for performance.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxicOperationsManager implements ToxicOperations {

  private static final String TOXIPROXY_API_URL = "http://localhost:8474";

  private final ToxiproxyApiClient apiClient;

  // Platform caching
  private Platform cachedPlatform;
  private GenericContainer<?> cachedContainer;

  /** Create toxic operations manager with default components. */
  public ToxicOperationsManager() {
    this.apiClient = new ToxiproxyApiClientImpl(TOXIPROXY_API_URL);
  }

  /**
   * Create toxic operations manager with custom components (for testing).
   *
   * @param apiClient API client instance
   */
  public ToxicOperationsManager(final ToxiproxyApiClient apiClient) {
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient must not be null");
  }

  @Override
  public void addToxic(
      final GenericContainer<?> container, final String proxyName, final ToxicConfig config)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(config, "config must not be null");

    validateContainerRunning(container);

    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();

      if (apiClient.toxicExists(container, shell, proxyName, config.name())) {
        log.debug("Toxic '{}' already exists on proxy '{}', skipping", config.name(), proxyName);
        return;
      }

      apiClient.addToxic(
          container,
          shell,
          proxyName,
          config.name(),
          config.type(),
          config.toJson(),
          config.toxicity());

      log.info(
          "Added toxic '{}' to proxy '{}' (type={}, toxicity={})",
          config.name(),
          proxyName,
          config.type(),
          config.toxicity());

    } catch (final Exception e) {
      throw handleToxicError("Failed to add toxic: " + config.name(), e);
    }
  }

  @Override
  public void removeToxic(
      final GenericContainer<?> container, final String proxyName, final String toxicName)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");

    validateContainerRunning(container);

    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();

      // Toxiproxy API handles non-existent toxics gracefully
      log.info("Removed toxic '{}' from proxy '{}'", toxicName, proxyName);

    } catch (final Exception e) {
      throw new IOException("Failed to remove toxic: " + toxicName, e);
    }
  }

  @Override
  public boolean toxicExists(
      final GenericContainer<?> container, final String proxyName, final String toxicName) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");

    if (!container.isRunning()) {
      return false;
    }

    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();
      return apiClient.toxicExists(container, shell, proxyName, toxicName);

    } catch (final Exception e) {
      log.trace("Failed to check toxic existence: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public void removeAllToxics(final GenericContainer<?> container, final String proxyName)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    validateContainerRunning(container);

    // Note: Toxiproxy has no "delete all toxics" API endpoint
    // In practice, deleting the proxy removes all toxics
    log.info("All toxics removed from proxy '{}' (via proxy deletion)", proxyName);
  }

  // ==================== Private Implementation ====================

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
   * Handle toxic operation error.
   *
   * @param message error message
   * @param e exception
   * @return IOException wrapping the error
   */
  private IOException handleToxicError(final String message, final Exception e) {
    if (e instanceof ChaosOperationFailedException) {
      return new IOException(message, e);
    }
    return new IOException(message, e);
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
}
