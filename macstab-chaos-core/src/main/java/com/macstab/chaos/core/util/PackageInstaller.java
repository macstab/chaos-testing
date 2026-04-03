/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.PackageInstallationException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PackageInstaller {

  /** Namespace prefix for tool-installation tracking labels on {@link GenericContainer}. */
  static final String LABEL_PREFIX = "macstab.chaos.pkg.";

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

    validateContainerRunning(container);

    final List<String> deduplicated = deduplicatePackages(packages);
    if (deduplicated.isEmpty()) {
      log.debug("No packages to install (empty list)");
      return;
    }

    final String containerId = container.getContainerId();
    logInstallStart(containerId, deduplicated);

    try {
      executeInstallation(container, deduplicated);
      if (verify) {
        verifyInstallation(container, deduplicated);
      }
    } catch (final Exception e) {
      handleInstallationError(e, containerId, deduplicated);
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
   * Ensures the given tools are installed in the container exactly once per container lifetime.
   *
   * <p>For each {@link ToolPackage}, installation is skipped if the label
   * {@code macstab.chaos.pkg.<tool>} is already present on the container's Java object.
   * The label is set via {@link GenericContainer#withLabel} — a pure in-JVM operation
   * with zero Docker API overhead. All unlabelled tools are collected, their packages
   * deduplicated, installed in a single package-manager invocation, verified by binary
   * name ({@code which <tool>}), then labelled.
   *
   * <p><strong>Algorithm:</strong>
   * <ol>
   *   <li>Check labels for each tool — collect only those not yet installed
   *   <li>If all present → return immediately (zero Docker cost)
   *   <li>Deduplicate packages of missing tools (e.g. taskset + renice → single util-linux)
   *   <li>Single {@code apk add} / {@code apt-get install} for all missing packages
   *   <li>Verify each missing tool binary via {@code which}
   *   <li>Set {@code macstab.chaos.pkg.<tool>=true} label for each newly installed tool
   * </ol>
   *
   * @param container target container (must be started)
   * @param tools     one or more tool-to-package bindings
   * @throws NullPointerException        if container or tools is null
   * @throws IllegalStateException       if container is not started
   * @throws PackageInstallationException if installation or verification fails
   */
  public static void ensureInstalled(
      final GenericContainer<?> container, final ToolPackage... tools) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(tools, "tools");
    validateContainerRunning(container);

    final List<ToolPackage> missing = collectMissing(container, tools);
    if (missing.isEmpty()) {
      log.debug("All tools already installed — skipping");
      return;
    }

    final List<String> packagesToInstall = deduplicatePackages(
        missing.stream().map(ToolPackage::packageName).toList());

    log.info("Installing {} package(s) for {} missing tool(s): {}",
        packagesToInstall.size(), missing.size(),
        missing.stream().map(ToolPackage::tool).toList());

    try {
      executeInstallation(container, packagesToInstall);
      verifyTools(container, missing);
      labelInstalled(container, missing);
    } catch (final Exception e) {
      handleInstallationError(e, container.getContainerId(), packagesToInstall);
    }
  }

  // ==================== Private: ensureInstalled helpers ====================

  /**
   * Returns the subset of tools not yet marked as installed on the container.
   *
   * @param container target container
   * @param tools     full set of required tools
   * @return tools whose label is absent from the container
   */
  private static List<ToolPackage> collectMissing(
      final GenericContainer<?> container, final ToolPackage[] tools) {
    final var labels = container.getLabels();
    final List<ToolPackage> missing = new ArrayList<>();
    for (final ToolPackage tool : tools) {
      Objects.requireNonNull(tool, "ToolPackage element must not be null");
      if (!labels.containsKey(LABEL_PREFIX + tool.tool())) {
        missing.add(tool);
      }
    }
    return missing;
  }

  /**
   * Verifies each tool binary is reachable via {@code which} after installation.
   *
   * <p>Verification uses the tool binary name — not the package name — to correctly
   * handle cases where they differ (e.g. package {@code util-linux} provides binary {@code taskset}).
   *
   * @param container target container
   * @param tools     tools to verify
   * @throws PackageInstallationException if any binary is not found
   */
  private static void verifyTools(
      final GenericContainer<?> container, final List<ToolPackage> tools) {
    for (final ToolPackage tool : tools) {
      verifyPackage(container, tool.tool());
    }
  }

  /**
   * Marks each tool as installed by setting its label on the container Java object.
   *
   * <p>Uses {@link GenericContainer#withLabel} which mutates the internal
   * {@code ContainerDef.labels} map directly — no Docker API call involved.
   *
   * @param container target container
   * @param tools     newly installed tools to label
   */
  private static void labelInstalled(
      final GenericContainer<?> container, final List<ToolPackage> tools) {
    for (final ToolPackage tool : tools) {
      container.withLabel(LABEL_PREFIX + tool.tool(), "true");
      log.debug("Labelled tool '{}' as installed", tool.tool());
    }
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
      log.warn("Failed to check package installation: {}", e.getMessage());
      return false;
    }
  }

  // ==================== Private Helper Methods ====================

  /**
   * Validates that container is running.
   *
   * @param container target container
   * @throws IllegalStateException if container is not started
   */
  private static void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException(
          "Container must be started before installing packages. Container ID: "
              + container.getContainerId());
    }
  }

  /**
   * Logs installation start message.
   *
   * @param containerId container ID
   * @param packages packages to install
   */
  private static void logInstallStart(final String containerId, final List<String> packages) {
    log.info(
        "Installing {} package(s) in container {}: {}",
        packages.size(),
        ContainerIdFormatter.truncate(containerId),
        packages);
  }

  /**
   * Executes package installation using detected package manager.
   *
   * @param container target container
   * @param packages packages to install
   * @throws Exception if installation fails
   */
  private static void executeInstallation(
      final GenericContainer<?> container, final List<String> packages) throws Exception {
    final PackageManager pm = PackageManager.detect(container);
    log.debug("Detected package manager: {}", pm.getCommand());

    final long startTime = System.currentTimeMillis();
    pm.install(container, packages.toArray(new String[0]));
    final long duration = System.currentTimeMillis() - startTime;

    log.info(
        "✓ Packages installed successfully in {}ms using {}: {}",
        duration,
        pm.getCommand(),
        packages);
  }

  /**
   * Handles installation errors by wrapping in PackageInstallationException.
   *
   * @param e original exception
   * @param containerId container ID
   * @param packages packages that failed
   * @throws PackageInstallationException always throws
   */
  private static void handleInstallationError(
      final Exception e, final String containerId, final List<String> packages) {
    if (e instanceof PackageInstallationException) {
      throw (PackageInstallationException) e;
    }
    throw new PackageInstallationException("Failed to install packages", containerId, packages, e);
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

    final Set<String> deduplicated = new LinkedHashSet<>(packages);

    if (deduplicated.size() < packages.size()) {
      log.debug(
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
    log.debug("Verifying installation of {} package(s)...", packages.size());

    for (final String pkg : packages) {
      verifyPackage(container, pkg);
    }

    log.info("✓ All {} package(s) verified successfully", packages.size());
  }

  /**
   * Verifies single package installation.
   *
   * @param container target container
   * @param pkg package name
   * @throws PackageInstallationException if verification fails
   */
  private static void verifyPackage(final GenericContainer<?> container, final String pkg) {
    try {
      final ExecResult result = container.execInContainer("which", pkg);

      if (result.getExitCode() == 0) {
        logPackageVerified(pkg, result.getStdout().trim());
      } else {
        throwVerificationFailed(container, pkg, result);
      }
    } catch (final Exception e) {
      if (e instanceof PackageInstallationException) {
        throw (PackageInstallationException) e;
      }
      throw new PackageInstallationException(
          "Failed to verify package installation", container.getContainerId(), List.of(pkg), e);
    }
  }

  /**
   * Logs successful package verification.
   *
   * @param pkg package name
   * @param path binary path
   */
  private static void logPackageVerified(final String pkg, final String path) {
    log.debug("✓ Package '{}' verified at: {}", pkg, path);
  }

  /**
   * Throws exception for failed package verification.
   *
   * @param container target container
   * @param pkg package name
   * @param result exec result
   * @throws PackageInstallationException always throws
   */
  private static void throwVerificationFailed(
      final GenericContainer<?> container, final String pkg, final ExecResult result) {
    log.warn("✗ Package '{}' verification failed: binary not found in PATH", pkg);
    throw new PackageInstallationException(
        "Package verification failed: '" + pkg + "' binary not found in PATH",
        container.getContainerId(),
        List.of(pkg),
        result.getExitCode(),
        result.getStdout(),
        result.getStderr());
  }
}
