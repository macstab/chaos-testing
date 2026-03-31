/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.lifecycle;

import java.io.IOException;
import java.util.Objects;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyInstaller;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of Toxiproxy lifecycle management.
 *
 * <p>Receives a pre-resolved {@link ContainerContext} on every call — no platform detection inside
 * this class. Platform is detected exactly once per operation by {@link
 * com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyLifecycleManager implements ToxiproxyLifecycle {

  private static final String TOXIPROXY_BINARY = "toxiproxy-server";

  /** Bind address for Toxiproxy server — all interfaces inside the container. */
  private static final String TOXIPROXY_BIND_ADDRESS = "0.0.0.0";

  /** Background process suffix — redirect output and detach from shell. */
  private static final String BACKGROUND_PROCESS_SUFFIX = ">/dev/null 2>&1 &";

  private final ToxiproxyConfig config;
  private final ToxiproxyInstaller installer;
  private final ToxiproxyApiClient apiClient;

  /**
   * Create lifecycle manager with default components.
   *
   * @param config Toxiproxy configuration
   */
  public ToxiproxyLifecycleManager(final ToxiproxyConfig config) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.installer = new ToxiproxyInstaller();
    this.apiClient = new ToxiproxyApiClientImpl(config.apiUrl());
  }

  /**
   * Create lifecycle manager with custom components (for testing).
   *
   * @param config Toxiproxy configuration
   * @param installer installer instance
   * @param apiClient API client instance
   */
  public ToxiproxyLifecycleManager(
      final ToxiproxyConfig config,
      final ToxiproxyInstaller installer,
      final ToxiproxyApiClient apiClient) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.installer = Objects.requireNonNull(installer, "installer must not be null");
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient must not be null");
  }

  @Override
  public void ensureRunning(final ContainerContext ctx) throws IOException {
    Objects.requireNonNull(ctx, "ctx must not be null");

    if (!ctx.container().isRunning()) {
      throw new IllegalStateException("Container must be running");
    }

    if (apiClient.isApiReady(ctx)) {
      log.debug("Toxiproxy already running");
      return;
    }

    log.debug("Starting Toxiproxy server");
    installer.install(ctx);
    startToxiproxyServer(ctx);
    waitForApiReady(ctx);
    log.info("Started Toxiproxy server");
  }

  @Override
  public void stop(final ContainerContext ctx) throws IOException {
    Objects.requireNonNull(ctx, "ctx must not be null");

    if (!ctx.container().isRunning()) {
      throw new IllegalStateException("Container must be running");
    }

    try {
      final var processBuilder = ctx.platform().getProcessCommandBuilder();
      final String killCmd = processBuilder.buildKillAllProcessesCommand(TOXIPROXY_BINARY);
      ctx.shell().exec(ctx.container(), killCmd);
      log.info("Stopped Toxiproxy server");
    } catch (final Exception e) {
      throw new IOException("Failed to stop Toxiproxy", e);
    }
  }

  @Override
  public boolean isHealthy(final ContainerContext ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");

    if (!ctx.container().isRunning()) {
      return false;
    }

    try {
      return apiClient.isApiReady(ctx);
    } catch (final Exception e) {
      log.trace("Health check failed: {}", e.getMessage());
      return false;
    }
  }

  // ==================== Private Helpers ====================

  /**
   * Start Toxiproxy server process in the background.
   *
   * @param ctx resolved container context
   * @throws ChaosOperationFailedException if the start command fails
   */
  private void startToxiproxyServer(final ContainerContext ctx) {
    try {
      final String startCmd =
          String.format(
              "%s -host %s %s",
              TOXIPROXY_BINARY, TOXIPROXY_BIND_ADDRESS, BACKGROUND_PROCESS_SUFFIX);
      ctx.shell().exec(ctx.container(), startCmd);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start Toxiproxy", e);
    }
  }

  /**
   * Poll until Toxiproxy API becomes ready or timeout is reached.
   *
   * <p>Reuses the same {@link ContainerContext} for every poll — no repeated platform detection.
   *
   * @param ctx resolved container context
   * @throws ChaosOperationFailedException if the API does not become ready within configured
   *     timeout
   */
  private void waitForApiReady(final ContainerContext ctx) {
    final long deadline = System.currentTimeMillis() + config.startupTimeoutMs();

    while (System.currentTimeMillis() < deadline) {
      if (apiClient.isApiReady(ctx)) {
        return;
      }
      sleepOrThrow(config.pollIntervalMs());
    }

    throw new ChaosOperationFailedException(
        "Toxiproxy did not start within " + config.startupTimeoutMs() + "ms");
  }

  /**
   * Sleep for the given duration. Restores interrupt flag and throws if interrupted.
   *
   * @param millis milliseconds to sleep
   * @throws ChaosOperationFailedException if the thread is interrupted
   */
  private void sleepOrThrow(final int millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChaosOperationFailedException("Interrupted while waiting for Toxiproxy startup", e);
    }
  }
}
