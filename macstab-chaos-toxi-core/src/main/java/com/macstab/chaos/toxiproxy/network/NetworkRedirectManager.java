/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.network;

import java.io.IOException;
import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;

import com.macstab.chaos.core.command.network.NetworkCommandBuilder;
import com.macstab.chaos.toxiproxy.context.ContainerContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of network port redirection using iptables.
 *
 * <p>Receives a pre-resolved {@link ContainerContext} — no platform detection or caching inside
 * this class.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class NetworkRedirectManager implements NetworkRedirect {

  @Override
  public void setupRedirect(final ContainerContext ctx, final int servicePort, final int proxyPort)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    validatePort(servicePort, "servicePort");
    validatePort(proxyPort, "proxyPort");

    try {
      final NetworkCommandBuilder network = ctx.platform().getNetworkCommandBuilder();
      final String redirectCmd = network.buildAddRedirectCommand(servicePort, proxyPort);
      final ExecResult result = ctx.shell().exec(ctx.container(), redirectCmd);

      if (result.getExitCode() != 0) {
        throw new IOException(
            String.format(
                "Failed to setup port redirect %d → %d: %s",
                servicePort, proxyPort, result.getStderr()));
      }

      log.debug("Setup port redirect: {} → {}", servicePort, proxyPort);

    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException(
          String.format("Failed to setup redirect %d → %d", servicePort, proxyPort), e);
    }
  }

  @Override
  public void removeRedirect(final ContainerContext ctx, final int servicePort, final int proxyPort)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    validatePort(servicePort, "servicePort");
    validatePort(proxyPort, "proxyPort");

    try {
      final NetworkCommandBuilder network = ctx.platform().getNetworkCommandBuilder();
      final String removeCmd = network.buildRemoveRedirectCommand(servicePort, proxyPort);
      final ExecResult result = ctx.shell().exec(ctx.container(), removeCmd);

      if (result.getExitCode() != 0) {
        throw new IOException(
            String.format(
                "Failed to remove port redirect %d → %d: %s",
                servicePort, proxyPort, result.getStderr()));
      }

      log.debug("Removed port redirect: {} → {}", servicePort, proxyPort);

    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException(
          String.format("Failed to remove redirect %d → %d", servicePort, proxyPort), e);
    }
  }

  @Override
  public void clearAllRedirects(final ContainerContext ctx) throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");

    try {
      final NetworkCommandBuilder network = ctx.platform().getNetworkCommandBuilder();
      final String clearCmd = network.buildClearRedirectsCommand();
      final ExecResult result = ctx.shell().exec(ctx.container(), clearCmd);

      if (result.getExitCode() != 0) {
        log.warn("Failed to clear redirects (may not exist): {}", result.getStderr());
      } else {
        log.debug("Cleared all port redirects");
      }

    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException("Failed to clear redirects", e);
    }
  }

  // ==================== Private Helpers ====================

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
