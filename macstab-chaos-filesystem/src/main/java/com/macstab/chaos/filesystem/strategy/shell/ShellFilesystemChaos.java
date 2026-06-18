/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.strategy.shell;

import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.spi.FilesystemChaosStrategy;
import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Coarse, container-wide filesystem chaos via standard shell commands ({@code dd}, {@code chmod},
 * {@code rm}).
 *
 * <p>This strategy is the portable, no-prepare-required half of the filesystem-module split. It
 * does not use FUSE despite the legacy class name {@code FuseFilesystemChaos} (now retired) — every
 * verb is implemented with a single in-container {@code exec}. Path-prefix targeting, per-syscall
 * fault injection, and probability-driven effects are out of scope here; those belong to {@code
 * LibchaosIoFilesystemChaos}.
 *
 * <h2>Capability scope</h2>
 *
 * <ul>
 *   <li>{@code fillDisk} — best-effort exhaustion of available space using {@code dd}
 *   <li>{@code injectPermissionErrors} — threshold-based {@code chmod 000} on a path
 *   <li>{@code reset} — cleans up the disk-fill file
 * </ul>
 *
 * <p>The advanced libchaos-io surface (per-path {@code EIO}/{@code ENOSPC} injection, torn writes,
 * read corruption, latency on durability barriers, …) is intentionally <em>not</em> implemented
 * here. The composite routes those verbs to the libchaos-io strategy via {@link
 * com.macstab.chaos.core.exception.ChaosUnsupportedOperationException} fall-through.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ShellFilesystemChaos implements FilesystemChaosStrategy {

  // Configuration
  private static final String DISK_FILL_PATH = "/tmp/chaos-disk-fill";
  private static final Pattern VALID_SIZE = Pattern.compile("^\\d+[KMG]$");
  private static final Pattern SAFE_PATH = Pattern.compile("^[a-zA-Z0-9/_.-]+$");

  // ==================== FilesystemChaosStrategy ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.isRunning();
  }

  // ==================== FilesystemChaos ====================

  @Override
  public void fillDisk(final GenericContainer<?> container, final String size) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(size, "size must not be null");

    validateContainerRunning(container);
    validateSizeFormat(size);

    try {
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
    } catch (final ChaosOperationFailedException e) {
      throw e;
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
      if (rate > 0.5) {
        final var result = container.execInContainer("chmod", "000", path);

        if (result.getExitCode() != 0) {
          throw new ChaosOperationFailedException(
              "Failed to change permissions on " + path + ": " + result.getStderr());
        }

        log.info("Removed permissions on {} (rate: {})", path, rate);
      } else {
        log.info("Permission error rate {} below threshold, no action taken", rate);
      }
    } catch (final ChaosOperationFailedException e) {
      throw e;
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
    Objects.requireNonNull(container, "container must not be null");
    // No special tools needed (dd, chmod, rm are standard)
  }

  // ==================== Helpers ====================

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

  private void validateSizeFormat(final String size) {
    final String upper = size.toUpperCase();
    if (!VALID_SIZE.matcher(upper).matches()) {
      throw new IllegalArgumentException("Invalid size format (must be digits + K/M/G): " + size);
    }
  }

  private void validatePath(final String path) {
    if (!SAFE_PATH.matcher(path).matches()) {
      throw new IllegalArgumentException("Invalid path (unsafe characters detected): " + path);
    }

    if (path.contains("..")) {
      throw new IllegalArgumentException("Path traversal not allowed: " + path);
    }
  }

  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }
}
