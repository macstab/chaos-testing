/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.network;

import java.io.IOException;
import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;

import com.macstab.chaos.core.command.network.NetworkCommandBuilder;
import com.macstab.chaos.toxiproxy.context.ContainerContext;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Production implementation of {@link NetworkRedirect} — manages iptables NAT rules inside
 * containers to transparently redirect TCP traffic through Toxiproxy.
 *
 * <h2>Design: No State, No Platform Detection</h2>
 *
 * <p>This class is stateless. It does not track which rules it has installed, does not cache the
 * container reference, and does not perform platform detection. All execution context arrives via
 * {@link ContainerContext}. The {@link
 * com.macstab.chaos.core.command.network.NetworkCommandBuilder} is retrieved from {@link
 * ContainerContext#platform()} on each call — if the platform abstraction changes the iptables
 * command syntax for a new distribution, no change to this class is required.
 *
 * <h2>Statelessness Implication: No Rule Tracking</h2>
 *
 * <p>Because this class does not track installed rules, it cannot know whether a given port pair
 * has an existing redirect, whether duplicates were added, or whether a previous cleanup failed
 * partially. Higher-level orchestration ({@link
 * com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator}) is responsible for ensuring setup and
 * teardown are symmetric. Rule tracking at this level would require shared mutable state across
 * instances, introducing concurrency risk for marginal benefit.
 *
 * <h2>Failure Handling Pattern</h2>
 *
 * <p>Each method wraps command execution in a try-catch that distinguishes:
 *
 * <ul>
 *   <li>Checked {@link IOException} from the shell layer — re-thrown directly.
 *   <li>Any other {@link Exception} — wrapped as {@link IOException} with context message.
 * </ul>
 *
 * This ensures callers only need to handle {@link IOException} regardless of whether the underlying
 * failure is a shell execution error, a Docker API error, or an unexpected runtime exception.
 *
 * <h2>Non-Zero Exit on clearAllRedirects</h2>
 *
 * <p>{@link #clearAllRedirects(ContainerContext)} logs a warning for non-zero exit codes rather
 * than throwing. The iptables flush command may exit non-zero if the nat table is empty or the
 * kernel module is in an unusual state. Since {@code clearAllRedirects} is called during teardown,
 * an exception here would prevent subsequent cleanup steps from running. Warning-only is the
 * correct trade-off for teardown operations.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see NetworkRedirect for the full interface contract including the two-chain rationale
 */
@Slf4j
public final class NetworkRedirectManager implements NetworkRedirect {

  @Override
  public void setupRedirect(
      @NonNull final ContainerContext ctx, final int servicePort, final int proxyPort)
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
  public void removeRedirect(
      @NonNull final ContainerContext ctx, final int servicePort, final int proxyPort)
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
  public void clearAllRedirects(@NonNull final ContainerContext ctx) throws IOException {

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
