/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.CpuChaos;
import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.Shell;

/**
 * CPU chaos using inside-container tools.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class CgroupsCpuChaos implements CpuChaos {

  @Override
  public void throttle(final GenericContainer<?> container, final int percentage) {
    Objects.requireNonNull(container, "container must not be null");
    validatePercentage(percentage);
    validateContainerRunning(container);

    installTools(container);
    killCpuLimit(container);

    final int pid = 1;

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format("cpulimit -l %d -p %d >/dev/null 2>&1 &", percentage, pid));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to start cpulimit: " + result.getStderr());
      }

      log.info("Throttled CPU to {}% (PID {})", percentage, pid);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to throttle CPU", e);
    }
  }

  @Override
  public void stress(final GenericContainer<?> container, final int workers) {
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
              String.format("stress-ng --cpu %d --timeout 0 >/dev/null 2>&1 &", workers));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to start stress-ng: " + result.getStderr());
      }

      log.info("Started CPU stress: {} workers", workers);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start CPU stress", e);
    }
  }

  @Override
  public void stress(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(duration, "duration must not be null");

    if (workers < 1) {
      throw new ChaosConfigurationException(
          String.format("workers must be >= 1, got: %d", workers));
    }

    validateContainerRunning(container);
    installTools(container);
    killStressNg(container);

    final long seconds = duration.toSeconds();

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "stress-ng --cpu %d --timeout %ds >/dev/null 2>&1 &", workers, seconds));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to start stress-ng: " + result.getStderr());
      }

      log.info("Started CPU stress: {} workers for {}s", workers, seconds);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start CPU stress with timeout", e);
    }
  }

  @Override
  public int getCurrentUsage(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);

    try {
      final var result = container.execInContainer("cat", "/proc/stat");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to read /proc/stat: " + result.getStderr());
      }

      log.debug("CPU stat: {}", result.getStdout());
      return 0; // TODO: Parse CPU usage properly
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to read CPU usage", e);
    }
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);

    log.debug("Installing CPU chaos tools (stress-ng, cpulimit)");
    PackageInstaller.install(container, "stress-ng", "cpulimit");
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    killCpuLimit(container);
    killStressNg(container);

    log.info("Reset CPU chaos");
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  private void validatePercentage(final int percentage) {
    if (percentage < 1 || percentage > 100) {
      throw new ChaosConfigurationException(
          String.format("percentage must be in [1, 100], got: %d", percentage));
    }
  }

  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }
  }

  private void killCpuLimit(final GenericContainer<?> container) {
    try {
      final var result = container.execInContainer("pkill", "-9", "cpulimit");
      if (result.getExitCode() == 0) {
        log.debug("Killed cpulimit processes");
      }
    } catch (final Exception ignored) {
      // No cpulimit running
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
