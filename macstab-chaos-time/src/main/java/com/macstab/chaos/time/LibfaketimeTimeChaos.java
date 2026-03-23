/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time;

import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.TimeChaos;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.Shell;

/**
 * Time chaos using libfaketime with dynamic updates.
 *
 * <p><strong>REQUIRES CONTAINER SETUP:</strong> Call {@link #enableDynamicTime(GenericContainer)}
 * before starting container.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class LibfaketimeTimeChaos implements TimeChaos {
  private static final String TIMESTAMP_FILE = "/tmp/faketime";

  @Override
  public void shift(final GenericContainer<?> container, final Duration offset) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(offset, "offset must not be null");

    validateContainerRunning(container);
    installTools(container);

    final String timespec = formatDuration(offset);

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format("printf '%%s' '%s' > %s", timespec, TIMESTAMP_FILE));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to write timestamp file: " + result.getStderr());
      }

      log.info("Shifted time by: {} ({})", offset, timespec);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "Failed to shift time - ensure container was configured with enableDynamicTime()", e);
    }
  }

  @Override
  public void drift(final GenericContainer<?> container, final double speedMultiplier) {
    Objects.requireNonNull(container, "container must not be null");

    if (speedMultiplier <= 0.0) {
      throw new IllegalArgumentException(
          String.format("speedMultiplier must be > 0.0, got: %.2f", speedMultiplier));
    }

    validateContainerRunning(container);
    installTools(container);

    final String timespec = "x" + speedMultiplier;

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format("printf '%%s' '%s' > %s", timespec, TIMESTAMP_FILE));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to write timestamp file: " + result.getStderr());
      }

      log.info("Set time drift: {}x speed", speedMultiplier);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "Failed to set time drift - ensure container was configured with enableDynamicTime()", e);
    }
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    log.debug("Installing libfaketime");
    PackageInstaller.install(container, "faketime");

    try {
      // Create timestamp file
      var result = container.execInContainer("touch", TIMESTAMP_FILE);
      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to create timestamp file");
      }

      result =
          container.execInContainer(Shell.SH, Shell.FLAG_C, "printf '+0s' > " + TIMESTAMP_FILE);

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to initialize timestamp file");
      }

      log.debug("Created timestamp file: {}", TIMESTAMP_FILE);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to setup timestamp file", e);
    }
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      // Reset offset
      final var result =
          container.execInContainer(Shell.SH, Shell.FLAG_C, "printf '+0s' > " + TIMESTAMP_FILE);

      if (result.getExitCode() != 0) {
        log.warn("Failed to reset timestamp file: {}", result.getStderr());
      }

      // Remove timestamp file
      container.execInContainer("rm", "-f", TIMESTAMP_FILE);

      log.info("Reset time chaos (removed timestamp file)");
    } catch (final Exception e) {
      log.warn("Failed to reset time chaos", e);
    }
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  /**
   * Enable dynamic time manipulation.
   *
   * <p><strong>MUST be called BEFORE container.start()</strong>
   */
  public static GenericContainer<?> enableDynamicTime(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    return container
        .withEnv("LD_PRELOAD", "/usr/lib/faketime/libfaketime.so.1")
        .withEnv("FAKETIME_TIMESTAMP_FILE", TIMESTAMP_FILE)
        .withEnv("FAKETIME_UPDATE_TIMESTAMP_FILE", "1");
  }

  /** Format Duration for libfaketime. */
  private String formatDuration(final Duration duration) {
    final long totalSeconds = Math.abs(duration.toSeconds());
    final String sign = duration.isNegative() ? "-" : "+";

    if (totalSeconds >= 86400 && totalSeconds % 86400 == 0) {
      return sign + (totalSeconds / 86400) + "d";
    } else if (totalSeconds >= 3600 && totalSeconds % 3600 == 0) {
      return sign + (totalSeconds / 3600) + "h";
    } else if (totalSeconds >= 60 && totalSeconds % 60 == 0) {
      return sign + (totalSeconds / 60) + "m";
    } else {
      return sign + totalSeconds + "s";
    }
  }

  /** Validate container is running. */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }
}
