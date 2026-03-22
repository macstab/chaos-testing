/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Utility for installing network tools (tc, iptables) in Redis containers.
 *
 * <p><strong>Purpose:</strong> Redis Docker images don't include network chaos tools by default.
 * This utility installs iproute2 (tc command) and iptables for network chaos engineering.
 *
 * <p><strong>Supported Images:</strong> ALL Linux distributions!
 *
 * <ul>
 *   <li>redis:7.4 (Debian-based) ✅
 *   <li>redis:7.4-alpine (Alpine-based) ✅
 *   <li>redis:7.4-alpine3.x (Alpine variants) ✅
 *   <li>Fedora/RHEL/CentOS-based images ✅
 *   <li>Arch Linux-based images ✅
 *   <li>openSUSE-based images ✅
 * </ul>
 *
 * <p><strong>Auto-Detection:</strong> Automatically detects the Linux distribution and uses the
 * appropriate package manager (apt-get, apk, dnf, yum, pacman, zypper).
 *
 * <p><strong>Performance Note:</strong> Installation takes 4-5 seconds per container due to apt-get
 * update/install. For faster tests, consider using a pre-built Docker image:
 *
 * <pre>{@code
 * FROM redis:7.4
 * RUN apt-get update && apt-get install -y --no-install-recommends iproute2 iptables && rm -rf /var/lib/apt/lists/*
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> This class is thread-safe and stateless.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
 *     .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
 * redis.start();
 *
 * // Install network tools
 * ContainerNetworkToolsInstaller.install(redis);
 *
 * // Now you can use NetworkChaosController
 * new NetworkChaosController(List.of(redis))
 *     .injectLatency(redis, Duration.ofMillis(100));
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class ContainerNetworkToolsInstaller {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ContainerNetworkToolsInstaller.class);

  /** Private constructor - utility class. */
  private ContainerNetworkToolsInstaller() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Installs network tools (iproute2, iptables) in a Redis container.
   *
   * <p><strong>What This Installs:</strong>
   *
   * <ul>
   *   <li><strong>iproute2</strong> - Provides {@code tc} command for traffic control (latency,
   *       packet loss, jitter)
   *   <li><strong>iptables</strong> - Provides firewall rules for network partitions
   * </ul>
   *
   * <p><strong>Auto-Detection:</strong> Automatically detects the Linux distribution (Debian,
   * Alpine, Fedora, Arch, etc.) and uses the appropriate package manager.
   *
   * <p><strong>Performance:</strong> Takes 4-5 seconds due to package manager update and install
   * operations.
   *
   * <p><strong>Verification:</strong> Automatically verifies {@code tc} command is available after
   * installation.
   *
   * @param container the container to install tools in (any Linux distribution)
   * @throws RuntimeException if installation fails
   * @throws NullPointerException if container is null
   */
  public static void install(final GenericContainer<?> container) {
    if (container == null) {
      throw new NullPointerException("container cannot be null");
    }

    final String containerId = container.getContainerId();

    try {
      LOGGER.debug("Installing network tools in container: {}", containerId);

      // Auto-detect package manager
      final PackageManager pm = PackageManager.detect(container);
      LOGGER.debug("Detected package manager: {}", pm.getCommand());

      // Install packages using detected package manager
      LOGGER.debug("Installing iproute2 and iptables using {}...", pm.getCommand());
      pm.install(container, "iproute2", "iptables");

      LOGGER.debug("Network tools installed successfully");

      // Verify tc command is available
      final var verifyResult = container.execInContainer("which", "tc");
      if (verifyResult.getExitCode() != 0) {
        throw new RuntimeException("tc command not found after installation!");
      }

      LOGGER.info(
          "✓ Network tools installed in container: {} using {} (tc at: {})",
          ContainerIdFormatter.truncate(containerId),
          pm.getCommand(),
          verifyResult.getStdout().trim());

    } catch (Exception e) {
      throw new RuntimeException("Failed to install network tools in container " + containerId, e);
    }
  }

  /**
   * Checks if network tools are already installed in a container.
   *
   * <p><strong>Use Case:</strong> Avoid reinstalling tools if already present.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * if (!ContainerNetworkToolsInstaller.isInstalled(redis)) {
   *     ContainerNetworkToolsInstaller.install(redis);
   * }
   * }</pre>
   *
   * @param container the container to check
   * @return {@code true} if tc command is available, {@code false} otherwise
   * @throws NullPointerException if container is null
   */
  public static boolean isInstalled(final GenericContainer<?> container) {
    if (container == null) {
      throw new NullPointerException("container cannot be null");
    }

    try {
      final var result = container.execInContainer("which", "tc");
      return result.getExitCode() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Installs network tools only if not already installed.
   *
   * <p><strong>Performance:</strong> Skips installation if tools already present (fast).
   *
   * @param container the container to install tools in
   * @throws RuntimeException if installation fails
   * @throws NullPointerException if container is null
   */
  public static void installIfNeeded(final GenericContainer<?> container) {
    if (!isInstalled(container)) {
      install(container);
    } else {
      LOGGER.debug(
          "Network tools already installed in container: {}",
          ContainerIdFormatter.truncate(container.getContainerId()));
    }
  }
}
