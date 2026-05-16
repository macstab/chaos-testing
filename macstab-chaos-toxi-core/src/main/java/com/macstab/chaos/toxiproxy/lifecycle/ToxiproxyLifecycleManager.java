/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.lifecycle;

import java.io.IOException;
import java.util.Objects;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Production implementation of {@link ToxiproxyLifecycle} — manages the Toxiproxy server process
 * lifecycle inside a container with explicit idempotency, polling-based startup, and correct
 * interrupt handling.
 *
 * <h2>Design: No Internal Platform Detection</h2>
 *
 * <p>This class receives a pre-resolved {@link ContainerContext} on every method call. It never
 * calls {@link com.macstab.chaos.core.platform.PlatformDetector} itself. The orchestrator that
 * creates a {@code ContainerContext} via {@link ContainerContext#of(GenericContainer)} performs
 * platform detection exactly once per top-level operation. This design is enforced by the API
 * contract: all methods accept {@code ContainerContext}, not {@code GenericContainer<?>}.
 *
 * <h2>Constructor Variants</h2>
 *
 * <p>Two constructors are provided:
 *
 * <ul>
 *   <li><strong>Single-arg constructor</strong> (production): wires default collaborators — {@link
 *       ToxiproxyInstaller} and {@link ToxiproxyApiClientImpl} constructed with the API URL from
 *       config. This is the constructor used by {@code
 *       com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator} and {@code
 *       com.macstab.chaos.connection.ToxiproxyConnectionChaos} (both in sibling modules).
 *   <li><strong>Three-arg constructor</strong> (testability): accepts pre-built collaborators for
 *       unit testing. Allows injecting mock {@link ToxiproxyInstaller} and {@link
 *       com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient} without Docker.
 * </ul>
 *
 * <h2>ensureRunning Idempotency</h2>
 *
 * <p>The {@link #ensureRunning(ContainerContext)} method is idempotent by construction: it calls
 * {@link com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient#isApiReady(ContainerContext)} first and
 * returns immediately if the API is healthy. Multiple modules may safely call {@code ensureRunning}
 * on the same container in sequence or interleaved without coordination.
 *
 * <h2>Startup Polling Loop</h2>
 *
 * <p>After launching the Toxiproxy background process, the implementation polls the API at
 * intervals of {@code config.pollIntervalMs()} (default: 100 ms) until either the API responds or
 * the deadline ({@code config.startupTimeoutMs()}) is exceeded. The deadline is computed as a
 * wall-clock epoch millisecond value ({@code System.currentTimeMillis() + timeout}). Each poll
 * iteration costs one Docker API round trip. The loop is not real-time precise — JVM scheduling
 * jitter may cause individual poll intervals to exceed {@code pollIntervalMs}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is effectively stateless after construction: all state is in the injected
 * collaborators and in the {@code ContainerContext} passed per-call. The collaborators ({@link
 * ToxiproxyInstaller} and {@link com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient}) are
 * themselves stateless. Concurrent calls from multiple threads are safe at the Java level but may
 * race on Toxiproxy process startup (see {@link ToxiproxyLifecycle} class-level notes).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ToxiproxyLifecycle for the interface contract, including shared-instance design notes
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
   * Creates a lifecycle manager with default collaborators.
   *
   * <p>Constructs a {@link ToxiproxyInstaller} and a {@link ToxiproxyApiClientImpl} using the API
   * URL from {@code config}. This is the production constructor used by orchestrators that create
   * this manager from a {@link ToxiproxyConfig}.
   *
   * @param config Toxiproxy configuration; must not be null
   * @throws NullPointerException if config is null
   */
  public ToxiproxyLifecycleManager(final ToxiproxyConfig config) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.installer = new ToxiproxyInstaller();
    this.apiClient = new ToxiproxyApiClientImpl(config.apiUrl());
  }

  /**
   * Creates a lifecycle manager with custom collaborators — intended for unit testing.
   *
   * <p>Accepts pre-built {@link ToxiproxyInstaller} and {@link
   * com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient} instances, enabling injection of mocks or
   * stubs. Use this constructor in tests to verify lifecycle behavior without a running Docker
   * container.
   *
   * @param config Toxiproxy configuration; must not be null
   * @param installer binary installer; must not be null
   * @param apiClient Toxiproxy API client; must not be null
   * @throws NullPointerException if any argument is null
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
  public void ensureRunning(@NonNull final ContainerContext ctx) throws IOException {
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
  public void shutdown(@NonNull final ContainerContext ctx) throws IOException {
    Objects.requireNonNull(ctx, "ctx must not be null");

    if (!ctx.container().isRunning()) {
      throw new IllegalStateException("Container must be running");
    }

    try {
      // 1. Flush ALL iptables NAT rules (nuclear — removes every module's redirects)
      final var networkBuilder = ctx.platform().getNetworkCommandBuilder();
      final String clearCmd = networkBuilder.buildClearRedirectsCommand();
      ctx.shell().exec(ctx.container(), clearCmd);

      // 2. Kill the Toxiproxy process (nuclear — destroys every module's proxies)
      final var processBuilder = ctx.platform().getProcessCommandBuilder();
      final String killCmd = processBuilder.buildKillAllProcessesCommand(TOXIPROXY_BINARY);
      ctx.shell().exec(ctx.container(), killCmd);

      log.info("Shutdown Toxiproxy: killed process + flushed all iptables NAT rules");
    } catch (final Exception e) {
      throw new IOException("Failed to shutdown Toxiproxy", e);
    }
  }

  @Override
  public boolean isHealthy(@NonNull final ContainerContext ctx) {
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
   * Launches the Toxiproxy binary as a background process bound to all container interfaces.
   *
   * <p>Executes {@code toxiproxy-server -host 0.0.0.0 >/dev/null 2>&1 &} via the container shell.
   * The {@code -host 0.0.0.0} flag is required so that Toxiproxy's API (port 8474) and proxy
   * listeners are reachable from outside the container — the default bind address ({@code
   * localhost}) would make them invisible to iptables PREROUTING redirected traffic originating
   * from external clients.
   *
   * <p>The trailing {@code &} detaches the process from the shell session. Without it, the {@code
   * execInContainer} call would block indefinitely.
   *
   * <p>stdout and stderr are redirected to {@code /dev/null} to prevent the Toxiproxy process from
   * holding the exec session open via the output stream.
   *
   * @param ctx resolved container context
   * @throws ChaosOperationFailedException if the shell command execution itself fails
   */
  private void startToxiproxyServer(@NonNull final ContainerContext ctx) {
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
   * Polls the Toxiproxy API until it responds or the configured startup timeout expires.
   *
   * <p>Uses a wall-clock deadline ({@code System.currentTimeMillis() + startupTimeoutMs}) rather
   * than a fixed iteration count, so JVM scheduling jitter does not systematically under-wait. Each
   * poll iteration reuses the same {@code ContainerContext} — no additional platform detection per
   * iteration. The loop exits as soon as {@link
   * com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient#isApiReady(ContainerContext)} returns {@code
   * true}.
   *
   * <p>On timeout: throws {@link IOException} (not unchecked), consistent with the {@link
   * #ensureRunning} throws clause. This ensures timeout failures are not silently swallowed by
   * callers that catch only checked exceptions.
   *
   * <p>On interrupt: restores the interrupt flag via {@code Thread.currentThread().interrupt()} and
   * throws {@link IOException}. Callers that catch {@link IOException} and continue should re-check
   * interrupt status if cancellation via interruption is required.
   *
   * @param ctx resolved container context; reused across all poll iterations
   * @throws IOException if timeout expires or the waiting thread is interrupted
   */
  private void waitForApiReady(@NonNull final ContainerContext ctx) throws IOException {
    final long deadline = System.currentTimeMillis() + config.startupTimeoutMs();

    while (System.currentTimeMillis() < deadline) {
      if (apiClient.isApiReady(ctx)) {
        return;
      }
      sleepOrThrow(config.pollIntervalMs());
    }

    throw new IOException("Toxiproxy did not start within " + config.startupTimeoutMs() + "ms");
  }

  /**
   * Sleep for the given duration. Restores interrupt flag and throws if interrupted.
   *
   * @param millis milliseconds to sleep
   * @throws ChaosOperationFailedException if the thread is interrupted
   */
  private void sleepOrThrow(final int millis) throws IOException {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for Toxiproxy startup", e);
    }
  }
}
