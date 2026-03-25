/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.network;

import java.io.IOException;
import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.command.network.NetworkCommandBuilder;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of network port redirection using iptables.
 *
 * <p>Caches platform detection per container for performance. Platform is detected once per
 * container and reused for all subsequent operations.
 *
 * <p><strong>Thread-safety:</strong> Platform is immutable, safe to cache. Container reference is
 * checked on each call to invalidate cache when container changes.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class NetworkRedirectManager implements NetworkRedirect {

  // Platform caching (lazy initialization)
  private Platform cachedPlatform;
  private GenericContainer<?> cachedContainer;

  @Override
  public void setupRedirect(
      final GenericContainer<?> container,
      final Shell shell,
      final int servicePort,
      final int proxyPort)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(shell, "shell must not be null");
    validatePort(servicePort, "servicePort");
    validatePort(proxyPort, "proxyPort");

    try {
      final Platform platform = getPlatform(container);
      final NetworkCommandBuilder network = platform.getNetworkCommandBuilder();
      final String redirectCmd = network.buildAddRedirectCommand(servicePort, proxyPort);

      final ExecResult result = shell.exec(container, redirectCmd);

      if (result.getExitCode() != 0) {
        throw new IOException(
            String.format(
                "Failed to setup port redirect %d → %d: %s",
                servicePort, proxyPort, result.getStderr()));
      }

      log.debug("Setup port redirect: {} → {}", servicePort, proxyPort);

    } catch (final Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException(
          String.format("Failed to setup redirect %d → %d", servicePort, proxyPort), e);
    }
  }

  @Override
  public void removeRedirect(
      final GenericContainer<?> container,
      final Shell shell,
      final int servicePort,
      final int proxyPort)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(shell, "shell must not be null");
    validatePort(servicePort, "servicePort");
    validatePort(proxyPort, "proxyPort");

    try {
      final Platform platform = getPlatform(container);
      final NetworkCommandBuilder network = platform.getNetworkCommandBuilder();
      final String removeCmd = network.buildRemoveRedirectCommand(servicePort, proxyPort);

      final ExecResult result = shell.exec(container, removeCmd);

      if (result.getExitCode() != 0) {
        throw new IOException(
            String.format(
                "Failed to remove port redirect %d → %d: %s",
                servicePort, proxyPort, result.getStderr()));
      }

      log.debug("Removed port redirect: {} → {}", servicePort, proxyPort);

    } catch (final Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException(
          String.format("Failed to remove redirect %d → %d", servicePort, proxyPort), e);
    }
  }

  @Override
  public void clearAllRedirects(final GenericContainer<?> container, final Shell shell)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(shell, "shell must not be null");

    try {
      final Platform platform = getPlatform(container);
      final NetworkCommandBuilder network = platform.getNetworkCommandBuilder();
      final String clearCmd = network.buildClearRedirectsCommand();

      final ExecResult result = shell.exec(container, clearCmd);

      if (result.getExitCode() != 0) {
        log.warn("Failed to clear redirects (may not exist): {}", result.getStderr());
      } else {
        log.debug("Cleared all port redirects");
      }

    } catch (final Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException("Failed to clear redirects", e);
    }
  }

  // ==================== Private Helpers ====================

  /**
   * Get platform for container, using cache when available.
   *
   * <p>Platform is detected once per container and cached. Cache is invalidated when container
   * reference changes.
   *
   * @param container target container
   * @return platform instance (cached or newly detected)
   */
  private Platform getPlatform(final GenericContainer<?> container) {
    if (cachedPlatform == null || cachedContainer != container) {
      cachedPlatform = PlatformDetector.detect(container);
      cachedContainer = container;
      log.trace("Platform detected and cached for container: {}", container.getDockerImageName());
    }
    return cachedPlatform;
  }

  /**
   * Validate port is in valid range.
   *
   * @param port port number
   * @param name parameter name
   * @throws IllegalArgumentException if invalid
   */
  private void validatePort(final int port, final String name) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(
          String.format("%s must be in range [1, 65535], got: %d", name, port));
    }
  }
}
