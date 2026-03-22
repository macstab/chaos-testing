/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

import java.util.List;
import java.util.Objects;

import com.macstab.chaos.core.util.ContainerIdFormatter;

/**
 * Exception thrown when package installation fails in a container.
 *
 * <p><strong>Purpose:</strong> Provides comprehensive error context including container ID,
 * packages that failed to install, exit code, and stdout/stderr output.
 *
 * <p><strong>Design:</strong> Immutable exception with rich error information for debugging.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * try {
 *     PackageInstaller.install(container, List.of("nonexistent-package"), true);
 * } catch (PackageInstallationException e) {
 *     System.err.println("Failed to install: " + e.getPackages());
 *     System.err.println("Container: " + e.getContainerId());
 *     System.err.println("Exit code: " + e.getExitCode());
 *     System.err.println("Error output: " + e.getStderr());
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class PackageInstallationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String containerId;
  private final List<String> packages;
  private final int exitCode;
  private final String stdout;
  private final String stderr;

  /**
   * Creates a new package installation exception.
   *
   * @param message error message (never null)
   * @param containerId container ID where installation failed (never null)
   * @param packages packages that failed to install (never null, never empty)
   * @param exitCode process exit code (non-zero)
   * @param stdout standard output from package manager (may be null)
   * @param stderr standard error from package manager (may be null)
   * @throws NullPointerException if message, containerId, or packages is null
   * @throws IllegalArgumentException if packages is empty or exitCode is 0
   */
  public PackageInstallationException(
      final String message,
      final String containerId,
      final List<String> packages,
      final int exitCode,
      final String stdout,
      final String stderr) {
    super(buildMessage(message, containerId, packages, exitCode, stdout, stderr));
    this.containerId = Objects.requireNonNull(containerId, "containerId");
    this.packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
    if (packages.isEmpty()) {
      throw new IllegalArgumentException("packages cannot be empty");
    }
    if (exitCode == 0) {
      throw new IllegalArgumentException("exitCode must be non-zero for failure (got: 0)");
    }
    this.exitCode = exitCode;
    this.stdout = stdout;
    this.stderr = stderr;
  }

  /**
   * Creates a new package installation exception with a cause.
   *
   * @param message error message (never null)
   * @param containerId container ID where installation failed (never null)
   * @param packages packages that failed to install (never null, never empty)
   * @param cause underlying cause (may be null)
   * @throws NullPointerException if message, containerId, or packages is null
   * @throws IllegalArgumentException if packages is empty
   */
  public PackageInstallationException(
      final String message,
      final String containerId,
      final List<String> packages,
      final Throwable cause) {
    super(buildMessageWithCause(message, containerId, packages, cause), cause);
    this.containerId = Objects.requireNonNull(containerId, "containerId");
    this.packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
    if (packages.isEmpty()) {
      throw new IllegalArgumentException("packages cannot be empty");
    }
    this.exitCode = -1; // Unknown exit code
    this.stdout = null;
    this.stderr = null;
  }

  /**
   * Gets the container ID where installation failed.
   *
   * @return container ID (never null)
   */
  public String getContainerId() {
    return containerId;
  }

  /**
   * Gets the packages that failed to install.
   *
   * @return unmodifiable list of package names (never null, never empty)
   */
  public List<String> getPackages() {
    return packages;
  }

  /**
   * Gets the process exit code.
   *
   * @return exit code (non-zero for failures, -1 if unknown)
   */
  public int getExitCode() {
    return exitCode;
  }

  /**
   * Gets the standard output from package manager.
   *
   * @return stdout content (may be null or empty)
   */
  public String getStdout() {
    return stdout;
  }

  /**
   * Gets the standard error from package manager.
   *
   * @return stderr content (may be null or empty)
   */
  public String getStderr() {
    return stderr;
  }

  /**
   * Builds comprehensive error message with all context.
   *
   * @param message base error message
   * @param containerId container ID
   * @param packages failed packages
   * @param exitCode process exit code
   * @param stdout standard output
   * @param stderr standard error
   * @return formatted error message
   */
  private static String buildMessage(
      final String message,
      final String containerId,
      final List<String> packages,
      final int exitCode,
      final String stdout,
      final String stderr) {
    final var sb = new StringBuilder();
    sb.append(Objects.requireNonNull(message, "message"));
    sb.append("\n");
    sb.append("Container: ").append(ContainerIdFormatter.truncate(containerId));
    sb.append("\n");
    sb.append("Packages: ").append(packages);
    sb.append("\n");
    sb.append("Exit code: ").append(exitCode);

    if (stdout != null && !stdout.isBlank()) {
      sb.append("\n");
      sb.append("stdout:\n").append(stdout.trim());
    }

    if (stderr != null && !stderr.isBlank()) {
      sb.append("\n");
      sb.append("stderr:\n").append(stderr.trim());
    }

    return sb.toString();
  }

  /**
   * Builds error message with cause.
   *
   * @param message base error message
   * @param containerId container ID
   * @param packages failed packages
   * @param cause underlying cause
   * @return formatted error message
   */
  private static String buildMessageWithCause(
      final String message,
      final String containerId,
      final List<String> packages,
      final Throwable cause) {
    final var sb = new StringBuilder();
    sb.append(Objects.requireNonNull(message, "message"));
    sb.append("\n");
    sb.append("Container: ").append(ContainerIdFormatter.truncate(containerId));
    sb.append("\n");
    sb.append("Packages: ").append(packages);

    if (cause != null) {
      sb.append("\n");
      sb.append("Cause: ").append(cause.getClass().getSimpleName());
      sb.append(": ").append(cause.getMessage());
    }

    return sb.toString();
  }

  /**
   * Truncates container ID to first 12 characters for readability.
   *
   * @param id container ID
   * @return truncated ID (12 chars) or original if shorter
   */
}
