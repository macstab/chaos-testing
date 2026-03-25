/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.lifecycle;

import java.io.IOException;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.api.ToxiproxyApiClient;
import com.macstab.chaos.proxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.proxy.internal.toxiproxy.ToxiproxyInstaller;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of Toxiproxy lifecycle management.
 *
 * <p>Handles installation, startup, health monitoring, and shutdown of Toxiproxy server.
 *
 * <p>Caches platform detection per container for performance.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyLifecycleManager implements ToxiproxyLifecycle {

  private static final String TOXIPROXY_API_URL = "http://localhost:8474";
  private static final String TOXIPROXY_BINARY = "toxiproxy-server";
  private static final int STARTUP_TIMEOUT_MS = 10000;
  private static final int POLL_INTERVAL_MS = 100;

  private final ToxiproxyInstaller installer;
  private final ToxiproxyApiClient apiClient;

  // Platform caching
  private Platform cachedPlatform;
  private GenericContainer<?> cachedContainer;

  /** Create lifecycle manager with default components. */
  public ToxiproxyLifecycleManager() {
    this.installer = new ToxiproxyInstaller();
    this.apiClient = new ToxiproxyApiClientImpl(TOXIPROXY_API_URL);
  }

  /**
   * Create lifecycle manager with custom components (for testing).
   *
   * @param installer installer instance
   * @param apiClient API client instance
   */
  public ToxiproxyLifecycleManager(
      final ToxiproxyInstaller installer, final ToxiproxyApiClient apiClient) {
    this.installer = Objects.requireNonNull(installer, "installer must not be null");
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient must not be null");
  }

  @Override
  public void ensureRunning(final GenericContainer<?> container) throws IOException {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }

    if (isHealthy(container)) {
      log.debug("Toxiproxy already running");
      return;
    }

    log.debug("Starting Toxiproxy server");
    installer.install(container);
    startToxiproxyServer(container);
    waitForApiReady(container);
    log.info("Started Toxiproxy server");
  }

  @Override
  public void stop(final GenericContainer<?> container) throws IOException {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }

    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();

      final String killCmd = "pkill -f " + TOXIPROXY_BINARY + " || true";
      shell.exec(container, killCmd);

      log.info("Stopped Toxiproxy server");

    } catch (final Exception e) {
      throw new IOException("Failed to stop Toxiproxy", e);
    }
  }

  @Override
  public boolean isHealthy(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return false;
    }

    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();
      return apiClient.isApiReady(container, shell);
    } catch (final Exception e) {
      log.trace("Health check failed: {}", e.getMessage());
      return false;
    }
  }

  // ==================== Private Implementation ====================

  /**
   * Start Toxiproxy server process in background.
   *
   * @param container target container
   * @throws ChaosOperationFailedException if start fails
   */
  private void startToxiproxyServer(final GenericContainer<?> container) {
    try {
      final Platform platform = getPlatform(container);
      final Shell shell = platform.getDefaultShell();

      final String startCmd = TOXIPROXY_BINARY + " -host 0.0.0.0 >/dev/null 2>&1 &";
      shell.exec(container, startCmd);

    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start Toxiproxy", e);
    }
  }

  /**
   * Wait for Toxiproxy API to become ready.
   *
   * @param container target container
   * @throws ChaosOperationFailedException if timeout reached
   */
  private void waitForApiReady(final GenericContainer<?> container) {
    final long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;

    while (System.currentTimeMillis() < deadline) {
      if (isHealthy(container)) {
        return;
      }

      sleep(POLL_INTERVAL_MS);
    }

    throw new ChaosOperationFailedException(
        "Toxiproxy did not start within " + STARTUP_TIMEOUT_MS + "ms");
  }

  /**
   * Sleep for specified milliseconds, ignoring interrupts.
   *
   * @param millis milliseconds to sleep
   */
  private void sleep(final int millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
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
}
