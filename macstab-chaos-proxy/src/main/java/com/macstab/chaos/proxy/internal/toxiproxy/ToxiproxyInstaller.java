/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.toxiproxy;

import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.model.ContainerArchitecture;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.proxy.internal.ContainerContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Installs the Toxiproxy binary into a container if not already present.
 *
 * <p>Uses {@link ContainerContext} for all container interactions — no direct {@code
 * execInContainer} calls, no hardcoded tool names.
 *
 * <p><strong>INTERNAL USE ONLY</strong> — implementation detail, not part of the public API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyInstaller {

  private static final String TOXIPROXY_VERSION = "v2.9.0";
  private static final String TOXIPROXY_BINARY = "toxiproxy-server";

  /** Standard Linux binary directory — writable and on PATH in all supported container images. */
  private static final String LINUX_BIN_DIR = "/usr/local/bin/";

  private static final String TOXIPROXY_INSTALL_PATH = LINUX_BIN_DIR + TOXIPROXY_BINARY;
  private static final String TOXIPROXY_DOWNLOAD_URL_TEMPLATE =
      "https://github.com/Shopify/toxiproxy/releases/download/%s/toxiproxy-server-linux-%s";

  /**
   * Install Toxiproxy binary into the container if not already present.
   *
   * @param ctx resolved container context
   * @throws NullPointerException if ctx is null
   * @throws ChaosOperationFailedException if installation fails
   */
  public void install(final ContainerContext ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");

    if (isAlreadyInstalled(ctx)) {
      log.debug("Toxiproxy already installed");
      return;
    }

    installDependencies(ctx);

    final ContainerArchitecture arch = ContainerArchitecture.detect(ctx.container());
    final String downloadUrl = buildDownloadUrl(arch);

    downloadBinary(ctx, downloadUrl);
    makeExecutable(ctx);

    log.debug("Installed Toxiproxy {} ({})", TOXIPROXY_VERSION, arch.getBinaryName());
  }

  // ==================== Private Helpers ====================

  /**
   * Check if the Toxiproxy binary is already installed.
   *
   * @param ctx container context
   * @return true if binary found in PATH
   */
  private boolean isAlreadyInstalled(final ContainerContext ctx) {
    try {
      final ExecResult result = ctx.shell().exec(ctx.container(), "which " + TOXIPROXY_BINARY);
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      return false;
    }
  }

  /**
   * Install required system packages (CA certificates, curl, iptables).
   *
   * @param ctx container context
   */
  private void installDependencies(final ContainerContext ctx) {
    final String caCertsPackage = ctx.platform().getPackageName(Tool.CA_CERTIFICATES);
    final String curlPackage = ctx.platform().getPackageName(Tool.CURL);
    final String iptablesPackage = ctx.platform().getPackageName(Tool.IPTABLES);

    // ca-certificates has no binary to verify — skip verification
    PackageInstaller.install(ctx.container(), List.of(caCertsPackage), false);

    // curl and iptables have binaries — verify installation
    PackageInstaller.install(ctx.container(), curlPackage, iptablesPackage);
  }

  /**
   * Build the GitHub release download URL for the detected container architecture.
   *
   * @param arch container CPU architecture
   * @return fully qualified download URL
   */
  private String buildDownloadUrl(final ContainerArchitecture arch) {
    return String.format(TOXIPROXY_DOWNLOAD_URL_TEMPLATE, TOXIPROXY_VERSION, arch.getBinaryName());
  }

  /**
   * Download the Toxiproxy binary using the platform HTTP command builder.
   *
   * @param ctx container context
   * @param downloadUrl source URL
   * @throws ChaosOperationFailedException if download fails
   */
  private void downloadBinary(final ContainerContext ctx, final String downloadUrl) {
    try {
      final String downloadCmd =
          ctx.http().buildDownloadRequest(downloadUrl, TOXIPROXY_INSTALL_PATH);
      final ExecResult result = ctx.shell().exec(ctx.container(), downloadCmd);

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to download Toxiproxy from " + downloadUrl + ": " + result.getStderr());
      }
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to download Toxiproxy", e);
    }
  }

  /**
   * Mark the downloaded binary as executable.
   *
   * @param ctx container context
   * @throws ChaosOperationFailedException if chmod fails
   */
  private void makeExecutable(final ContainerContext ctx) {
    try {
      final ExecResult result =
          ctx.shell().exec(ctx.container(), "chmod +x " + TOXIPROXY_INSTALL_PATH);

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to chmod Toxiproxy binary: " + result.getStderr());
      }
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to make Toxiproxy executable", e);
    }
  }
}
