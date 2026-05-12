/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.strategy.cgroups;

import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.model.MemoryPressureInfo;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.spi.MemoryChaosStrategy;
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
public final class CgroupsMemoryChaos implements MemoryChaosStrategy {

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.isRunning();
  }

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
      // Launch stress-ng under a tiny shell-reaper wrapper.
      //
      // The wrapper exists to solve the zombie-on-kill problem: many test images (redis:7.4 et al.)
      // have a PID 1 that does not reap orphans, so a SIGKILL'd stress-ng would linger as a zombie
      // and {@code pgrep stress-ng} would still find it after reset(). Running stress-ng as a child
      // of an intermediate {@code sh -c} keeps the shell as its parent; when stress-ng dies the
      // shell's implicit wait() returns and the kernel reaps stress-ng. The shell itself becomes
      // a zombie under PID 1 but its comm is {@code sh}, so {@code pgrep stress-ng} no longer
      // matches.
      //
      // {@code setsid} detaches the whole tree from the docker-exec session so it survives the
      // exec call returning; the subshell {@code (...)} makes the launch fire-and-forget.
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "(setsid sh -c 'stress-ng --vm 1 --vm-bytes %d --timeout 0' "
                      + ">/dev/null 2>&1 </dev/null &)",
                  bytes));

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
    // PROCPS provides pgrep/pkill, which the strategy's own reset() uses and which downstream
    // tooling and tests rely on for process introspection. Minimal Debian images (bookworm-slim
    // base of redis:7.4) ship without it.
    PackageInstaller.ensureInstalled(container, Tool.STRESS_NG, Tool.PROCPS);
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
      // Send SIGTERM first so stress-ng's parent process gets a chance to clean up its own
      // worker tree (stress-ng → stress-ng-vm [wait] → stress-ng-vm [run]). Under SIGKILL the
      // parent dies instantly and its children are reparented to PID 1 (redis-server in the test
      // image), which does not reap orphans — leaving zombies that {@code pgrep stress-ng} still
      // finds and which break {@code shouldReset} / {@code shouldKillStressProcesses} assertions.
      final var term = container.execInContainer("pkill", "stress-ng");
      if (term.getExitCode() == 0) {
        log.debug("Sent SIGTERM to stress-ng processes; waiting for graceful shutdown");
      }

      // Poll until the entire process tree is gone; if it persists, escalate to SIGKILL.
      final long deadline = System.currentTimeMillis() + 3_000L;
      boolean gone = false;
      while (System.currentTimeMillis() < deadline) {
        final var probe = container.execInContainer("pgrep", "stress-ng");
        if (probe.getExitCode() != 0) {
          gone = true;
          break;
        }
        Thread.sleep(50L);
      }
      if (!gone) {
        // Escalate — SIGKILL the remaining processes. Any resulting zombies are a known
        // limitation when stress-ng's parent has already died.
        log.debug("stress-ng survived SIGTERM; escalating to SIGKILL");
        container.execInContainer("pkill", "-9", "stress-ng");
        final long escDeadline = System.currentTimeMillis() + 1_500L;
        while (System.currentTimeMillis() < escDeadline) {
          final var probe = container.execInContainer("pgrep", "stress-ng");
          if (probe.getExitCode() != 0) {
            break;
          }
          Thread.sleep(50L);
        }
      }
      log.debug("Killed stress-ng processes");
    } catch (final Exception ignored) {
      // No stress-ng running
    }
  }
}
