/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.PackageInstallationException;

/**
 * High-level orchestrator for installing packages in containers with deduplication and
 * verification.
 *
 * <p><strong>Purpose:</strong> Provides a clean API for package installation that handles:
 *
 * <ul>
 *   <li>Automatic deduplication (preserving order)
 *   <li>Linux distribution detection
 *   <li>Package manager selection
 *   <li>Installation verification
 *   <li>Comprehensive error handling
 * </ul>
 *
 * <p><strong>Design Principles:</strong>
 *
 * <ul>
 *   <li>Idempotent: Can run multiple times safely
 *   <li>Fast-fail: Throws exception immediately on error
 *   <li>Observable: Comprehensive logging at all levels
 *   <li>Defensive: Validates all inputs
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * GenericContainer<?> redis = new GenericContainer<>("redis:7.4");
 * redis.start();
 *
 * // Install packages with automatic verification
 * PackageInstaller.install(redis, List.of("curl", "jq", "curl"), true);
 * // Deduplicates to: ["curl", "jq"]
 * // Auto-detects: Debian → apt-get
 * // Installs: apt-get install -y --no-install-recommends curl jq
 * // Verifies: which curl && which jq
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class PackageInstaller {

  private static final Logger LOGGER = LoggerFactory.getLogger(PackageInstaller.class);

  /** Private constructor - utility class. */
  private PackageInstaller() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Installs packages in container with deduplication and verification.
   *
   * <p><strong>What This Does:</strong>
   *
   * <ol>
   *   <li>Deduplicates packages (preserves order, removes duplicates)
   *   <li>Detects Linux distribution and package manager
   *   <li>Installs packages using appropriate package manager
   *   <li>Verifies installation (if verify = true)
   * </ol>
   *
   * <p><strong>Performance:</strong>
   *
   * <ul>
   *   <li>Debian/Ubuntu: ~4-5 seconds (apt-get update + install)
   *   <li>Alpine: ~2-3 seconds (apk update + add)
   *   <li>Fedora: ~5-6 seconds (dnf install)
   * </ul>
   *
   * <p><strong>Error Handling:</strong>
   *
   * <ul>
   *   <li>Package not found → {@link PackageInstallationException}
   *   <li>Container not started → {@link IllegalStateException}
   *   <li>Verification failed → {@link PackageInstallationException}
   * </ul>
   *
   * @param container target container (must be started)
   * @param packages packages to install (duplicates auto-removed)
   * @param verify whether to verify installation with 'which' command
   * @throws NullPointerException if container or packages is null
   * @throws IllegalStateException if container is not started
   * @throws PackageInstallationException if installation or verification fails
   */
  public static void install(
      final GenericContainer<?> container,
      final Collection<String> packages,
      final boolean verify) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(packages, "packages");

    // Validate container is started
    if (!container.isRunning()) {
      throw new IllegalStateException(
          "Container must be started before installing packages. Container ID: "
              + container.getContainerId());
    }

    // Deduplicate packages (preserve order)
    final List<String> deduplicated = deduplicatePackages(packages);

    // Empty packages = no-op
    if (deduplicated.isEmpty()) {
      LOGGER.debug("No packages to install (empty list)");
      return;
    }

    final String containerId = container.getContainerId();
    LOGGER.info(
        "Installing {} package(s) in container {}: {}",
        deduplicated.size(),
        ContainerIdFormatter.truncate(containerId),
        deduplicated);

    try {
      // Detect package manager
      final PackageManager pm = PackageManager.detect(container);
      LOGGER.debug("Detected package manager: {}", pm.getCommand());

      // Install packages
      final long startTime = System.currentTimeMillis();
      pm.install(container, deduplicated.toArray(new String[0]));
      final long duration = System.currentTimeMillis() - startTime;

      LOGGER.info(
          "✓ Packages installed successfully in {}ms using {}: {}",
          duration,
          pm.getCommand(),
          deduplicated);

      // Verify installation
      if (verify) {
        verifyInstallation(container, deduplicated);
      }

    } catch (final Exception e) {
      // Wrap in PackageInstallationException if not already
      if (e instanceof PackageInstallationException) {
        throw (PackageInstallationException) e;
      }
      throw new PackageInstallationException(
          "Failed to install packages", containerId, deduplicated, e);
    }
  }

  /**
   * Installs packages with verification enabled (convenience method).
   *
   * @param container target container (must be started)
   * @param packages packages to install
   * @throws NullPointerException if container or packages is null
   * @throws IllegalStateException if container is not started
   * @throws PackageInstallationException if installation or verification fails
   */
  public static void install(final GenericContainer<?> container, final String... packages) {
    install(container, Arrays.asList(packages), true);
  }

  /**
   * Checks if packages are already installed in container.
   *
   * <p><strong>Implementation:</strong> Uses 'which' command to check if package binaries exist in
   * PATH.
   *
   * <p><strong>Limitations:</strong>
   *
   * <ul>
   *   <li>Only checks if binary exists, not if package is fully installed
   *   <li>Package name might differ from binary name (e.g., "iproute2" vs "tc")
   * </ul>
   *
   * @param container target container
   * @param packages package names to check
   * @return true if ALL packages are installed, false otherwise
   * @throws NullPointerException if container or packages is null
   */
  public static boolean isInstalled(final GenericContainer<?> container, final String... packages) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(packages, "packages");

    if (packages.length == 0) {
      return true; // Vacuous truth: zero packages = all installed
    }

    try {
      for (final String pkg : packages) {
        final var result = container.execInContainer("which", pkg);
        if (result.getExitCode() != 0) {
          return false; // Package not found
        }
      }
      return true; // All packages found
    } catch (final Exception e) {
      LOGGER.warn("Failed to check package installation: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Deduplicates packages while preserving order.
   *
   * <p><strong>Algorithm:</strong> Uses LinkedHashSet to maintain insertion order while removing
   * duplicates.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * Input:  ["curl", "jq", "curl", "vim", "jq"]
   * Output: ["curl", "jq", "vim"]
   * </pre>
   *
   * @param packages packages with possible duplicates
   * @return deduplicated list (preserves order)
   */
  static List<String> deduplicatePackages(final Collection<String> packages) {
    Objects.requireNonNull(packages, "packages");

    // LinkedHashSet preserves insertion order + removes duplicates
    final Set<String> deduplicated = new LinkedHashSet<>(packages);

    if (deduplicated.size() < packages.size()) {
      LOGGER.debug(
          "Deduplicated {} packages to {} (removed {} duplicates)",
          packages.size(),
          deduplicated.size(),
          packages.size() - deduplicated.size());
    }

    return List.copyOf(deduplicated);
  }

  /**
   * Verifies that packages are installed by checking with 'which' command.
   *
   * <p><strong>Note:</strong> Package name might differ from binary name. For example:
   *
   * <ul>
   *   <li>Package "iproute2" → Binary "tc"
   *   <li>Package "postgresql-client" → Binary "psql"
   * </ul>
   *
   * <p>This method checks the PACKAGE NAME, not the binary name.
   *
   * @param container target container
   * @param packages packages to verify
   * @throws PackageInstallationException if verification fails
   */
  private static void verifyInstallation(
      final GenericContainer<?> container, final List<String> packages) {
    LOGGER.debug("Verifying installation of {} package(s)...", packages.size());

    for (final String pkg : packages) {
      try {
        final var result = container.execInContainer("which", pkg);
        if (result.getExitCode() == 0) {
          final String path = result.getStdout().trim();
          LOGGER.debug("✓ Package '{}' verified at: {}", pkg, path);
        } else {
          // Package not found - verification failed
          LOGGER.warn("✗ Package '{}' verification failed: binary not found in PATH", pkg);
          throw new PackageInstallationException(
              "Package verification failed: '" + pkg + "' binary not found in PATH",
              container.getContainerId(),
              List.of(pkg),
              result.getExitCode(),
              result.getStdout(),
              result.getStderr());
        }
      } catch (final Exception e) {
        if (e instanceof PackageInstallationException) {
          throw (PackageInstallationException) e;
        }
        throw new PackageInstallationException(
            "Failed to verify package installation", container.getContainerId(), List.of(pkg), e);
      }
    }

    LOGGER.info("✓ All {} package(s) verified successfully", packages.size());
  }

  /**
   * Truncates container ID to first 12 characters for readability.
   *
   * @param id container ID
   * @return truncated ID (12 chars) or original if shorter
   */
}
