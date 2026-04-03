/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory;

import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.MemoryChaos;
import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.model.MemoryPressureInfo;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.ResourceParser;
import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Memory chaos using inside-container tools.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CgroupsMemoryChaos implements MemoryChaos {
  private static final Pattern VALID_SIZE = Pattern.compile("^\\d+[KMG]$");
  private static final long MAX_MEMORY_BYTES = 128L * 1024 * 1024 * 1024; // 128GB

  @Override
  public void setLimit(final GenericContainer<?> container, final String limit) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(limit, "limit must not be null");

    validateSizeFormat(limit);

    log.warn(
        "setLimit() requires container recreation - set memory limit at container creation time!");
    log.info("Memory limit should be: {}", limit);
  }

  @Override
  public void setPressure(final GenericContainer<?> container, final String threshold) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(threshold, "threshold must not be null");

    validateSizeFormat(threshold);

    final long bytes = ResourceParser.parseMemoryBytes(threshold);
    stress(container, threshold);

    log.info("Set memory pressure threshold: {} ({} bytes)", threshold, bytes);
  }

  @Override
  public void stress(final GenericContainer<?> container, final String size) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(size, "size must not be null");

    validateSizeFormat(size);
    validateContainerRunning(container);

    final long bytes = ResourceParser.parseMemoryBytes(size);

    if (bytes > MAX_MEMORY_BYTES) {
      throw new ChaosConfigurationException(
          String.format("Size too large (max 128GB): %s (%d bytes)", size, bytes));
    }

    installTools(container);
    killStressNg(container);

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format("stress-ng --vm 1 --vm-bytes %d --timeout 0 >/dev/null 2>&1 &", bytes));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to start stress-ng: " + result.getStderr());
      }

      log.info("Started memory stress: {} ({} bytes)", size, bytes);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start memory stress", e);
    }
  }

  @Override
  public long getCurrentUsage(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);

    try {
      final var result = container.execInContainer("cat", "/proc/meminfo");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to read /proc/meminfo: " + result.getStderr());
      }

      log.debug("Memory info: {}", result.getStdout());
      return 0; // TODO: Parse properly
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to read memory usage", e);
    }
  }

  @Override
  public MemoryPressureInfo getPressure(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    log.warn("getPressure() requires cgroups v2 access - returning zeros");
    return new MemoryPressureInfo(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
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
    log.info("Reset memory chaos");
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  private void validateSizeFormat(final String size) {
    final String upper = size.toUpperCase();
    if (!VALID_SIZE.matcher(upper).matches()) {
      throw new ChaosConfigurationException(
          "Invalid size format (must be digits + K/M/G): " + size);
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
