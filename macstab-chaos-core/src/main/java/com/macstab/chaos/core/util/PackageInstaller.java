/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.PackageInstallationException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.platform.Tool;

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
   * Ensures the given {@link Tool}s are installed exactly once per container lifetime.
   *
   * <p>Primary API for chaos modules. Uses the {@link Platform} layer to resolve the correct
   * package name and binary name per distribution — callers never deal with distro differences.
   *
   * <p><strong>Algorithm:</strong>
   *
   * <ol>
   *   <li>Check {@code macstab.chaos.pkg.<tool>} label on the container Java object for each tool
   *   <li>All present → return immediately (zero Docker cost, pure in-JVM map lookup)
   *   <li>Detect platform once — resolves {@code apk} / {@code apt-get} / {@code dnf}
   *   <li>Resolve package name per tool via {@link Platform#getPackageName(Tool)}
   *   <li>Deduplicate packages — single install invocation regardless of tool count
   *   <li>Verify binary via {@code which} using {@link Platform#getBinaryName(Tool)} (skipped for
   *       tools with no binary, e.g. {@link Tool#CA_CERTIFICATES})
   *   <li>Set label via {@link GenericContainer#withLabel} — pure in-JVM, no Docker API call
   * </ol>
   *
   * @param container target container (must be started)
   * @param tools one or more known tools from the {@link Tool} enum
   * @throws NullPointerException if container or tools is null
   * @throws IllegalStateException if container is not started
   * @throws PackageInstallationException if installation or verification fails
   */
  public static void ensureInstalled(final GenericContainer<?> container, final Tool... tools) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(tools, "tools");
    validateContainerRunning(container);

    final List<Tool> missing = collectMissingTools(container, tools);
    if (missing.isEmpty()) {
      log.debug("All tools already installed — skipping");
      return;
    }

    final Platform platform = PlatformDetector.detect(container);
    final List<String> packages =
        deduplicatePackages(missing.stream().map(platform::getPackageName).toList());

    log.info(
        "Installing {} package(s) for {} missing tool(s): {}",
        packages.size(),
        missing.size(),
        missing);

    try {
      executeInstallation(container, packages);
      verifyToolBinaries(container, missing, platform);
    } catch (final Exception e) {
      handleInstallationError(e, container.getContainerId(), packages);
    }
  }

  /**
   * Ensures the given tools are installed exactly once per container lifetime.
   *
   * <p>Open extension point — accepts any {@link ToolDefinition} implementation: built-in {@link
   * ToolPackage} records, or user-defined enums/classes from any module. No core changes required
   * to add new tools.
   *
   * <p>Uses the same label-guard, dedup, single-install, and verify algorithm as {@link
   * #ensureInstalled(GenericContainer, Tool...)}. Label key is {@code
   * macstab.chaos.pkg.<ToolDefinition.tool()>}. Verification uses the binary name directly.
   *
   * @param container target container (must be started)
   * @param tools one or more {@link ToolDefinition} instances
   * @throws NullPointerException if container or tools is null, or any element is null
   * @throws IllegalStateException if container is not started
   * @throws PackageInstallationException if installation or verification fails
   */
  public static void ensureInstalled(
      final GenericContainer<?> container, final ToolDefinition... tools) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(tools, "tools");
    validateContainerRunning(container);

    final List<ToolDefinition> missing = collectMissingDefinitions(container, tools);
    if (missing.isEmpty()) {
      log.debug("All tools already installed — skipping");
      return;
    }

    final List<String> packages =
        deduplicatePackages(missing.stream().map(ToolDefinition::packageName).toList());

    log.info(
        "Installing {} package(s) for {} missing tool(s): {}",
        packages.size(),
        missing.size(),
        missing.stream().map(ToolDefinition::tool).toList());

    try {
      executeInstallation(container, packages);
      missing.forEach(td -> verifyPackage(container, td.tool()));
      missing.forEach(td -> container.withLabel(LABEL_PREFIX + td.tool(), "true"));
    } catch (final Exception e) {
      handleInstallationError(e, container.getContainerId(), packages);
    }
  }

  // ==================== Private: ensureInstalled helpers ====================

  /** Collects {@link Tool} entries whose label is absent from the container. */
  private static List<Tool> collectMissingTools(
      final GenericContainer<?> container, final Tool[] tools) {
    final var labels = container.getLabels();
    final List<Tool> missing = new ArrayList<>();
    for (final Tool tool : tools) {
      Objects.requireNonNull(tool, "Tool element must not be null");
      if (!labels.containsKey(LABEL_PREFIX + tool.name().toLowerCase())) {
        missing.add(tool);
      }
    }
    return missing;
  }

  /** Collects {@link ToolDefinition} entries whose label is absent from the container. */
  private static List<ToolDefinition> collectMissingDefinitions(
      final GenericContainer<?> container, final ToolDefinition[] tools) {
    final var labels = container.getLabels();
    final List<ToolDefinition> missing = new ArrayList<>();
    for (final ToolDefinition td : tools) {
      Objects.requireNonNull(td, "ToolDefinition element must not be null");
      if (!labels.containsKey(LABEL_PREFIX + td.tool())) {
        missing.add(td);
      }
    }
    return missing;
  }

  /**
   * Verifies tool binaries via {@code which} and sets installation labels.
   *
   * <p>Tools with no binary (e.g. {@link Tool#CA_CERTIFICATES}) have {@link
   * Platform#getBinaryName(Tool)} returning {@code null} — verification is skipped for those, but
   * the label is still set.
   */
  private static void verifyToolBinaries(
      final GenericContainer<?> container, final List<Tool> tools, final Platform platform) {
    for (final Tool tool : tools) {
      final String binary = platform.getBinaryName(tool);
      if (binary != null) {
        verifyPackage(container, binary);
      }
      container.withLabel(LABEL_PREFIX + tool.name().toLowerCase(), "true");
      log.debug("Labelled tool '{}' as installed", tool);
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
