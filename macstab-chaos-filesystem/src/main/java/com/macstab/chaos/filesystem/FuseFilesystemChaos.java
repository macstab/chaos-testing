/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem;

import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.FilesystemChaos;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * Filesystem chaos using simple shell commands.
 *
 * <p><strong>Available methods (v1.0):</strong>
 *
 * <ul>
 *   <li>{@link #fillDisk(GenericContainer, String)} - Fill disk with garbage data
 *   <li>{@link #injectPermissionErrors(GenericContainer, String, double)} - Remove permissions
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class FuseFilesystemChaos implements FilesystemChaos {

  // Configuration
  private static final String DISK_FILL_PATH = "/tmp/chaos-disk-fill";
  private static final Pattern VALID_SIZE = Pattern.compile("^\\d+[KMG]$");
  private static final Pattern SAFE_PATH = Pattern.compile("^[a-zA-Z0-9/_.-]+$");

  @Override
  public void fillDisk(final GenericContainer<?> container, final String size) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(size, "size must not be null");

    validateContainerRunning(container);
    validateSizeFormat(size);

    try {
      // Use dd to create large file
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "dd if=/dev/zero of=%s bs=1M count=%s 2>&1", DISK_FILL_PATH, parseSize(size)));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to fill disk: " + result.getStderr());
      }

      log.info("Filled disk with {} garbage data at {}", size, DISK_FILL_PATH);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to fill disk", e);
    }
  }

  @Override
  public void injectPermissionErrors(
      final GenericContainer<?> container, final String path, final double rate) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(path, "path must not be null");

    if (rate < 0.0 || rate > 1.0) {
      throw new IllegalArgumentException(
          String.format("rate must be in [0.0, 1.0], got: %.2f", rate));
    }

    validateContainerRunning(container);
    validatePath(path);

    try {
      // Remove permissions if rate > 50%
      if (rate > 0.5) {
        final var result = container.execInContainer("chmod", "000", path);

        if (result.getExitCode() != 0) {
          throw new ChaosOperationFailedException(
              "Failed to change permissions on " + path + ": " + result.getStderr());
        }

        log.info("Removed permissions on {} (rate: {:.0%})", path, rate);
      } else {
        log.info("Permission error rate {:.0%} below threshold, no action taken", rate);
      }
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to inject permission errors", e);
    }
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      // Remove disk fill file
      final var result = container.execInContainer("rm", "-f", DISK_FILL_PATH);

      if (result.getExitCode() != 0) {
        log.warn("Failed to remove disk fill file: {}", result.getStderr());
      }

      log.info("Reset filesystem chaos (removed {})", DISK_FILL_PATH);
    } catch (final Exception e) {
      log.warn("Failed to reset filesystem chaos", e);
    }
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    // No special tools needed (dd, chmod, rm are standard)
  }

  /**
   * Parse and validate size string.
   *
   * @param size size string (e.g., "500M", "1G")
   * @return megabyte count
   */
  private int parseSize(final String size) {
    final String upper = size.toUpperCase();

    try {
      if (upper.endsWith("M")) {
        return Integer.parseInt(upper.substring(0, upper.length() - 1));
      } else if (upper.endsWith("G")) {
        final int gb = Integer.parseInt(upper.substring(0, upper.length() - 1));
        if (gb > 2000) {
          throw new IllegalArgumentException("Size too large (max 2000G): " + size);
        }
        return gb * 1024;
      } else if (upper.endsWith("K")) {
        final int kb = Integer.parseInt(upper.substring(0, upper.length() - 1));
        return Math.max(1, kb / 1024);
      } else {
        throw new IllegalArgumentException("Size must end with K/M/G, got: " + size);
      }
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException("Invalid size format: " + size, e);
    }
  }

  /** Validate size format. */
  private void validateSizeFormat(final String size) {
    final String upper = size.toUpperCase();
    if (!VALID_SIZE.matcher(upper).matches()) {
      throw new IllegalArgumentException("Invalid size format (must be digits + K/M/G): " + size);
    }
  }

  /** Validate path is safe (prevent injection). */
  private void validatePath(final String path) {
    if (!SAFE_PATH.matcher(path).matches()) {
      throw new IllegalArgumentException("Invalid path (unsafe characters detected): " + path);
    }

    if (path.contains("..")) {
      throw new IllegalArgumentException("Path traversal not allowed: " + path);
    }
  }

  /** Validate container is running. */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }
}
