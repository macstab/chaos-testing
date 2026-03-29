/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.lifecycle;

import java.io.IOException;

import com.macstab.chaos.proxy.internal.ContainerContext;

/**
 * Manages the lifecycle of the Toxiproxy server process inside a container.
 *
 * <p>Toxiproxy is a binary that must be installed, started, and stopped as a background process
 * within the target container. This interface abstracts that lifecycle, separating it cleanly
 * from proxy and toxic CRUD operations.
 *
 * <h2>Responsibility</h2>
 *
 * <ul>
 *   <li><strong>Installation:</strong> Downloads the Toxiproxy binary from GitHub releases if
 *       not already present in the container.</li>
 *   <li><strong>Startup:</strong> Launches {@code toxiproxy-server -host 0.0.0.0} as a
 *       background process and waits for the HTTP API to respond on port 8474.</li>
 *   <li><strong>Health:</strong> Checks whether the Toxiproxy API is alive by issuing
 *       {@code GET /proxies} via the container shell. Returns {@code false} on any error —
 *       never throws.</li>
 *   <li><strong>Stop:</strong> Sends a kill signal to the {@code toxiproxy-server} process
 *       using a platform-appropriate process command.</li>
 * </ul>
 *
 * <h2>Context Passing</h2>
 *
 * <p>All methods receive a pre-resolved {@link ContainerContext}. Platform detection is the
 * caller's responsibility, performed exactly once per operation in
 * {@link com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator}. This avoids repeated
 * {@code cat /etc/os-release} calls across the lifecycle, proxy, and toxic managers.
 *
 * <h2>Default Implementation</h2>
 *
 * <p>{@link ToxiproxyLifecycleManager} is the production implementation. Inject a custom
 * implementation via the
 * {@link com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator#ToxiproxyOrchestrator(
 * ToxiproxyLifecycle,
 * com.macstab.chaos.proxy.internal.operations.ProxyOperations,
 * com.macstab.chaos.proxy.internal.operations.ToxicOperations,
 * com.macstab.chaos.proxy.network.NetworkRedirect)} constructor for testing or alternative
 * Toxiproxy deployment strategies.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ToxiproxyLifecycle {

  /**
   * Ensure the Toxiproxy server is installed, running, and healthy.
   *
   * <p>This method is idempotent: if Toxiproxy is already running and the API is healthy,
   * it returns immediately without any side effects. Otherwise it performs the full
   * install → start → wait cycle.
   *
   * <p>The startup sequence:
   * <ol>
   *   <li>Check {@link #isHealthy(ContainerContext)} — return if already healthy.</li>
   *   <li>Download and install Toxiproxy binary (if not present).</li>
   *   <li>Start {@code toxiproxy-server} as a background process.</li>
   *   <li>Poll {@link #isHealthy(ContainerContext)} until the API responds or the configured
   *       startup timeout is exceeded.</li>
   * </ol>
   *
   * @param ctx resolved container context (container must be running)
   * @throws IOException if installation or startup fails
   * @throws IllegalStateException if {@code ctx.container().isRunning()} returns {@code false}
   */
  void ensureRunning(ContainerContext ctx) throws IOException;

  /**
   * Stop the Toxiproxy server process.
   *
   * <p>Sends a kill signal to the {@code toxiproxy-server} process inside the container.
   * Safe to call when Toxiproxy is not running — the underlying kill command may report an
   * error that is silently ignored.
   *
   * <p>After a successful stop, {@link #isHealthy(ContainerContext)} will return {@code false}.
   *
   * @param ctx resolved container context (container must be running)
   * @throws IOException if the stop command fails unexpectedly
   * @throws IllegalStateException if {@code ctx.container().isRunning()} returns {@code false}
   */
  void stop(ContainerContext ctx) throws IOException;

  /**
   * Check whether the Toxiproxy HTTP API is alive and responding.
   *
   * <p>Issues {@code GET /proxies} against the Toxiproxy API URL via the container shell.
   * Returns {@code true} if the command exits with code 0 (HTTP 200). Returns {@code false}
   * for any failure — network error, process not running, container stopped — and never throws.
   *
   * <p>This method is safe to call in a polling loop without try/catch.
   *
   * @param ctx resolved container context
   * @return {@code true} if the Toxiproxy API is responding, {@code false} otherwise
   */
  boolean isHealthy(ContainerContext ctx);
}
