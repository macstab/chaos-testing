/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.lifecycle;

import java.io.IOException;

import org.testcontainers.containers.GenericContainer;

/**
 * Lifecycle management for Toxiproxy server.
 *
 * <p>Handles installation, startup, health checks, and shutdown of the Toxiproxy server process
 * within containers.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@link ToxiproxyLifecycleManager} - Default implementation
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ToxiproxyLifecycle {

  /**
   * Ensure Toxiproxy server is running and healthy.
   *
   * <p>If not running, installs and starts the server. Waits for API to become ready.
   *
   * @param container target container
   * @throws IOException if installation or startup fails
   * @throws IllegalStateException if container is not running
   */
  void ensureRunning(GenericContainer<?> container) throws IOException;

  /**
   * Stop Toxiproxy server gracefully.
   *
   * <p>Terminates the toxiproxy-server process and cleans up resources.
   *
   * @param container target container
   * @throws IOException if stop fails
   */
  void stop(GenericContainer<?> container) throws IOException;

  /**
   * Check if Toxiproxy API is healthy and responding.
   *
   * @param container target container
   * @return true if API is ready, false otherwise
   */
  boolean isHealthy(GenericContainer<?> container);
}
