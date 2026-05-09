/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ProcessChaos;
import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.model.ProcessInfo;
import com.macstab.chaos.core.model.Signal;

import lombok.extern.slf4j.Slf4j;

/**
 * Process chaos using inside-container commands.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CgroupsProcessChaos implements ProcessChaos {
  private static final Pattern SAFE_PROCESS_NAME = Pattern.compile("^[a-zA-Z0-9_.-]+$");

  @Override
  public void kill(
      final GenericContainer<?> container, final String processName, final Signal signal) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(processName, "processName must not be null");
    Objects.requireNonNull(signal, "signal must not be null");

    validateProcessName(processName);
    validateContainerRunning(container);

    try {
      final var result = container.execInContainer("pkill", "-" + signal.value(), processName);

      if (result.getExitCode() != 0 && !result.getStderr().isEmpty()) {
        log.warn("pkill returned non-zero ({}), process may not exist", result.getExitCode());
      }

      log.info("Sent signal {} to process '{}'", signal, processName);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "Failed to send signal " + signal + " to " + processName, e);
    }
  }

  @Override
  public void pause(
      final GenericContainer<?> container, final String processName, final Duration duration) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(processName, "processName must not be null");
    Objects.requireNonNull(duration, "duration must not be null");

    if (duration.isNegative() || duration.isZero()) {
      throw new ChaosConfigurationException("duration must be positive, got: " + duration);
    }

    validateProcessName(processName);
    validateContainerRunning(container);

    try {
      // Pause process
      final var result = container.execInContainer("pkill", "-STOP", processName);

      if (result.getExitCode() != 0 && !result.getStderr().isEmpty()) {
        throw new ChaosOperationFailedException("Failed to pause process: " + result.getStderr());
      }

      log.info("Paused process '{}' for {}ms", processName, duration.toMillis());

      // Resume after duration
      new Thread(
              () -> {
                try {
                  Thread.sleep(duration.toMillis());
                  resume(container, processName);
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  log.warn("Resume interrupted for process '{}'", processName);
                }
              },
              "chaos-resume-" + processName)
          .start();
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to pause process " + processName, e);
    }
  }

  @Override
  public void limitProcesses(final GenericContainer<?> container, final int maxProcesses) {
    if (maxProcesses < 1) {
      throw new ChaosConfigurationException(
          String.format("maxProcesses must be >= 1, got: %d", maxProcesses));
    }

    log.warn("limitProcesses() requires cgroups pids controller - not implemented");
    log.info("Requested max processes: {}", maxProcesses);
  }

  @Override
  public List<ProcessInfo> listProcesses(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateContainerRunning(container);

    try {
      final Container.ExecResult result = container.execInContainer("ps", "-eo", "pid,comm");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to list processes: " + result.getStderr());
      }

      final String[] lines = result.getStdout().split("\n");
      final List<ProcessInfo> processes = new ArrayList<>();

      for (int i = 1; i < lines.length; i++) {
        final String line = lines[i].trim();
        if (line.isEmpty()) {
          continue;
        }

        final String[] parts = line.split("\\s+", 2);
        if (parts.length == 2) {
          try {
            final int pid = Integer.parseInt(parts[0]);
            final String name = parts[1];
            processes.add(new ProcessInfo(pid, name));
          } catch (final NumberFormatException e) {
            log.warn("Failed to parse PID from line: {}", line);
          }
        }
      }

      log.debug("Found {} processes", processes.size());
      return processes;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to list processes", e);
    }
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    log.debug("Process chaos tools (ps, pkill) are pre-installed");
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      // Resume all processes
      final var result = container.execInContainer("pkill", "-CONT", ".*");

      if (result.getExitCode() == 0) {
        log.info("Reset process chaos (resumed all processes)");
      }
    } catch (final Exception e) {
      log.warn("Failed to reset process chaos", e);
    }
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  private void resume(final GenericContainer<?> container, final String processName) {
    try {
      final var result = container.execInContainer("pkill", "-CONT", processName);

      if (result.getExitCode() == 0) {
        log.info("Resumed process '{}'", processName);
      }
    } catch (final Exception e) {
      log.warn("Failed to resume process '{}': {}", processName, e.getMessage());
    }
  }

  private void validateProcessName(final String processName) {
    if (!SAFE_PROCESS_NAME.matcher(processName).matches()) {
      throw new IllegalArgumentException(
          "Invalid process name (unsafe characters): " + processName);
    }
  }

  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }
  }
}
