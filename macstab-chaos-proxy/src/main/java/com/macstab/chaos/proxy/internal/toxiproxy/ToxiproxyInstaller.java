/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.toxiproxy;

import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.model.ContainerArchitecture;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Internal installer for Toxiproxy binary.
 *
 * <p><strong>INTERNAL USE ONLY</strong> - Implementation detail, not part of public API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyInstaller {

  private static final String TOXIPROXY_VERSION = "v2.9.0";
  private static final String TOXIPROXY_BINARY = "toxiproxy-server";
  private static final String TOXIPROXY_DOWNLOAD_URL_TEMPLATE =
      "https://github.com/Shopify/toxiproxy/releases/download/%s/toxiproxy-server-linux-%s";

  /**
   * Install Toxiproxy binary if not already present.
   *
   * @param container container
   */
  public void install(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (isAlreadyInstalled(container)) {
      log.debug("Toxiproxy already installed");
      return;
    }

    try {
      final Platform platform = PlatformDetector.detect(container);
      installDependencies(container, platform);

      final ContainerArchitecture arch = ContainerArchitecture.detect(container);
      final String downloadUrl = buildDownloadUrl(arch);

      downloadAndInstallBinary(container, downloadUrl);

      log.debug("Installed Toxiproxy {} ({})", TOXIPROXY_VERSION, arch.getBinaryName());

    } catch (final Exception e) {
      handleInstallationError(e);
    }
  }

  // ==================== Private Helper Methods ====================

  /** Check if Toxiproxy is already installed. */
  private boolean isAlreadyInstalled(final GenericContainer<?> container) {
    try {
      final ExecResult result = container.execInContainer("which", TOXIPROXY_BINARY);
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      return false;
    }
  }

  /** Install required dependencies using platform-specific package names. */
  private void installDependencies(final GenericContainer<?> container, final Platform platform) {
    // Get platform-specific package names
    final String caCertsPackage = platform.getPackageName(Tool.CA_CERTIFICATES);
    final String curlPackage = platform.getPackageName(Tool.CURL);
    final String iptablesPackage = platform.getPackageName(Tool.IPTABLES);

    // ca-certificates has no binary, skip verification
    PackageInstaller.install(container, List.of(caCertsPackage), false);

    // curl and iptables have binaries, verify installation
    PackageInstaller.install(container, curlPackage, iptablesPackage);
  }

  /** Build download URL for architecture. */
  private String buildDownloadUrl(final ContainerArchitecture arch) {
    return String.format(TOXIPROXY_DOWNLOAD_URL_TEMPLATE, TOXIPROXY_VERSION, arch.getBinaryName());
  }

  /** Download and install Toxiproxy binary. */
  private void downloadAndInstallBinary(
      final GenericContainer<?> container, final String downloadUrl) throws Exception {

    final String downloadCmd =
        String.format(
            "curl -sL %s -o /usr/local/bin/%s && chmod +x /usr/local/bin/%s",
            downloadUrl, TOXIPROXY_BINARY, TOXIPROXY_BINARY);

    final ExecResult result = container.execInContainer(Shell.SH, Shell.FLAG_C, downloadCmd);

    if (result.getExitCode() != 0) {
      throw new ChaosOperationFailedException(
          "Failed to download Toxiproxy: " + result.getStderr());
    }
  }

  /** Handle installation error. */
  private void handleInstallationError(final Exception e) {
    if (e instanceof ChaosOperationFailedException) {
      throw (ChaosOperationFailedException) e;
    }
    throw new ChaosOperationFailedException("Failed to install Toxiproxy", e);
  }
}
