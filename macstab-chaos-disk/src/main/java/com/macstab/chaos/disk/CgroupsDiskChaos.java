/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.DiskChaos;
import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Disk chaos using inside-container tools.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CgroupsDiskChaos implements DiskChaos {
  private static final Pattern SAFE_PATH = Pattern.compile("^[a-zA-Z0-9/_.-]+$");
  private static final int MAX_FILL_PERCENTAGE = 95; // Safety limit

  @Override
  public void limitWriteBandwidth(
      final GenericContainer<?> container, final String bytesPerSecond) {
    log.warn("limitWriteBandwidth() requires cgroups access - not implemented");
    log.info("Requested write bandwidth limit: {}", bytesPerSecond);
  }

  @Override
  public void limitReadBandwidth(final GenericContainer<?> container, final String bytesPerSecond) {
    log.warn("limitReadBandwidth() requires cgroups access - not implemented");
    log.info("Requested read bandwidth limit: {}", bytesPerSecond);
  }

  @Override
  public void limitReadIOPS(final GenericContainer<?> container, final int iops) {
    log.warn("limitReadIOPS() requires cgroups access - not implemented");
    log.info("Requested read IOPS limit: {}", iops);
  }

  @Override
  public void limitWriteIOPS(final GenericContainer<?> container, final int iops) {
    log.warn("limitWriteIOPS() requires cgroups access - not implemented");
    log.info("Requested write IOPS limit: {}", iops);
  }

  @Override
  public void fillDisk(
      final GenericContainer<?> container, final String mountPoint, final int percentage) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(mountPoint, "mountPoint must not be null");

    if (percentage < 1 || percentage > MAX_FILL_PERCENTAGE) {
      throw new ChaosConfigurationException(
          String.format("percentage must be in [1, %d], got: %d", MAX_FILL_PERCENTAGE, percentage));
    }

    validatePath(mountPoint);
    validateContainerRunning(container);
    installTools(container);

    try {
      // Get disk size
      final var dfResult = container.execInContainer("df", "-k", mountPoint);

      if (dfResult.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to get disk size: " + dfResult.getStderr());
      }

      final String[] lines = dfResult.getStdout().split("\n");
      if (lines.length < 2) {
        throw new ChaosOperationFailedException("Failed to parse df output");
      }

      final String[] fields = lines[1].trim().split("\\s+");
      final long totalKB = Long.parseLong(fields[1]);
      final long fillKB = (totalKB * percentage) / 100;

      // Remove old load file
      final String loadFile = mountPoint + "/chaos-load";
      container.execInContainer("rm", "-f", loadFile);

      // Create new file
      final var ddResult =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format("dd if=/dev/zero of=%s bs=1K count=%d 2>&1", loadFile, fillKB));

      if (ddResult.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to create disk fill file: " + ddResult.getStderr());
      }

      log.info("Filled disk {} to {}% ({} KB)", mountPoint, percentage, fillKB);
    } catch (final NumberFormatException e) {
      throw new ChaosOperationFailedException("Failed to parse disk size", e);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to fill disk", e);
    }
  }

  @Override
  public void stressDisk(final GenericContainer<?> container, final int workers) {
    Objects.requireNonNull(container, "container must not be null");

    if (workers < 1) {
      throw new ChaosConfigurationException(
          String.format("workers must be >= 1, got: %d", workers));
    }

    validateContainerRunning(container);
    installTools(container);
    killStressNg(container);

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format("stress-ng --hdd %d --timeout 0 >/dev/null 2>&1 &", workers));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to start stress-ng: " + result.getStderr());
      }

      log.info("Started disk stress: {} workers", workers);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start disk stress", e);
    }
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    PackageInstaller.ensureInstalled(container, Tool.STRESS_NG);
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    killStressNg(container);

    try {
      // Remove chaos-load files
      final var result = container.execInContainer("find", "/", "-name", "chaos-load", "-delete");

      if (result.getExitCode() != 0) {
        log.warn("Failed to remove chaos-load files: {}", result.getStderr());
      }

      log.info("Reset disk chaos");
    } catch (final Exception e) {
      log.warn("Failed to reset disk chaos", e);
    }
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  private void validatePath(final String path) {
    if (!SAFE_PATH.matcher(path).matches()) {
      throw new IllegalArgumentException("Invalid path (unsafe characters): " + path);
    }

    if (path.contains("..")) {
      throw new IllegalArgumentException("Path traversal not allowed: " + path);
    }
  }

  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }
  }

  private void killStressNg(final GenericContainer<?> container) {
    try {
      final var result = container.execInContainer("pkill", "-9", "stress-ng");
      if (result.getExitCode() == 0) {
        log.debug("Killed stress-ng processes");
      }
    } catch (final Exception ignored) {
      // No stress-ng running
    }
  }
}
