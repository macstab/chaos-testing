/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.lifecycle;

import java.util.List;
import java.util.Objects;
import lombok.NonNull;

import org.testcontainers.containers.Container.ExecResult;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.model.ContainerArchitecture;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.toxiproxy.context.ContainerContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Installs the Toxiproxy binary and its system-level dependencies into a container on demand,
 * with idempotency and architecture-aware binary selection.
 *
 * <h2>Why On-Demand Installation</h2>
 *
 * <p>Toxiproxy is not pre-installed in standard Redis, PostgreSQL, or other service container
 * images. Installing it as part of the base image would require maintaining custom images or
 * Dockerfile patches for every service image used in testing — an unacceptable maintenance
 * burden. Instead, this class installs Toxiproxy at runtime, the first time it is needed,
 * directly into a running container. The installation is idempotent: subsequent calls are
 * no-ops after a {@code which toxiproxy-server} check confirms the binary is present.
 *
 * <h2>Installation Sequence</h2>
 *
 * <ol>
 *   <li><strong>Idempotency check</strong>: runs {@code which toxiproxy-server} in the container.
 *       Returns immediately if the binary is found on PATH.
 *   <li><strong>Dependency installation</strong>: installs system packages using the
 *       platform-appropriate package manager (apt-get, apk, dnf). Three packages:
 *       <ul>
 *         <li>{@code ca-certificates} — required for TLS verification when downloading from
 *             GitHub over HTTPS. Installed with {@code verify=false} because the ca-certificates
 *             package provides no binary.
 *         <li>{@code curl} (or platform-appropriate HTTP client) — used to download the binary.
 *         <li>{@code iptables} — required by
 *             {@link com.macstab.chaos.toxiproxy.network.NetworkRedirectManager} for port
 *             redirection. Installed here as part of the Toxiproxy setup, since the installer
 *             runs before any proxy is created.
 *       </ul>
 *   <li><strong>Architecture detection</strong>: calls {@link ContainerArchitecture#detect} which
 *       runs {@code uname -m} inside the container. Maps {@code x86_64/amd64} → {@code amd64},
 *       {@code aarch64/arm64} → {@code arm64}. Selects the correct GitHub release binary.
 *   <li><strong>Binary download</strong>: downloads the architecture-specific
 *       {@code toxiproxy-server} binary from GitHub Releases using the platform HTTP command
 *       builder ({@code curl -sL ...}). Writes to {@code /usr/local/bin/toxiproxy-server}.
 *   <li><strong>chmod</strong>: makes the binary executable ({@code chmod +x}).
 * </ol>
 *
 * <h2>Network Dependency</h2>
 *
 * <p>Step 4 requires the container to have outbound internet access to
 * {@code https://github.com/Shopify/toxiproxy/releases/}. In air-gapped or network-restricted
 * environments, this will fail. Workaround: pre-install the binary in the container image, or
 * mount it as a volume. Once installed, the binary persists for the container's lifetime.
 *
 * <h2>Fixed Version</h2>
 *
 * <p>The Toxiproxy version is hardcoded to {@code v2.9.0}. This is intentional: chaos testing
 * relies on specific toxic behaviors whose semantics may change across versions. A floating
 * "latest" reference would make test behavior non-deterministic across CI runs. Version updates
 * must be explicit and accompanied by validation of toxic behavior changes.
 *
 * <h2>Platform Abstraction</h2>
 *
 * <p>This class uses {@link ContainerContext#http()} for HTTP commands and
 * {@link ContainerContext#platform()} for package name resolution. Hardcoded tool names
 * ({@code curl}, {@code apt-get}) would break on Alpine (uses {@code wget}/{@code apk}).
 * The platform abstraction ensures the same installer works across Debian, Ubuntu, Alpine,
 * and RHEL-family images without conditional logic in this class.
 *
 * <h2>Internal API</h2>
 *
 * <p>This class is an internal implementation detail consumed exclusively by
 * {@link ToxiproxyLifecycleManager}. It is not a stable public API. Callers outside the
 * {@code lifecycle} package should not reference this class directly.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ToxiproxyLifecycleManager which orchestrates this installer within the startup sequence
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
   * Installs the Toxiproxy binary and its dependencies into the container, if not already present.
   *
   * <p><strong>Idempotency:</strong> The first operation is {@code which toxiproxy-server}. If
   * exit code 0, returns immediately. This check costs one Docker API round trip (~5–100 ms)
   * and is the common path after the first invocation per container.
   *
   * <p><strong>Side effects on success:</strong> The following are installed in the container:
   * the {@code ca-certificates} package, the platform-specific {@code curl} package,
   * the platform-specific {@code iptables} package, and the {@code toxiproxy-server} binary
   * at {@code /usr/local/bin/toxiproxy-server} (mode 755).
   *
   * <p><strong>Failure behavior:</strong> Any step failure throws
   * {@link ChaosOperationFailedException} (unchecked). This is consistent with the caller
   * ({@link ToxiproxyLifecycleManager#ensureRunning}), which wraps this in a
   * {@code try/catch} and re-throws as {@link java.io.IOException}.
   *
   * <p><strong>Container mutation:</strong> This method permanently modifies the container's
   * filesystem. The installed packages and binary persist for the container's lifetime.
   * Testcontainers containers are ephemeral by design, so this is acceptable: each test run
   * starts fresh containers and the installation happens at most once per container per run.
   *
   * @param ctx resolved container context; the container must be running and have outbound
   *            internet access for the first-time installation path
   * @throws NullPointerException if ctx is null
   * @throws ChaosOperationFailedException if package installation, binary download, or chmod
   *                                       fails; wraps the underlying cause
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
    PackageInstaller.ensureInstalled(ctx.container(),
        Tool.CA_CERTIFICATES, Tool.CURL, Tool.IPTABLES);
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
