/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.DiskChaos;
import com.macstab.chaos.core.command.disk.DiskCommandBuilder;
import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.syscall.DiskErrno;
import com.macstab.chaos.core.syscall.DiskOperation;
import com.macstab.chaos.core.syscall.SyscallFaultInjector;
import com.macstab.chaos.core.syscall.SyscallRule;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.Shell;
import com.macstab.chaos.disk.command.StressNgDiskCommandBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * Disk chaos implementation using userspace tools and syscall fault injection.
 *
 * <h2>Two injection layers</h2>
 *
 * <ul>
 *   <li><strong>Process-level</strong> — {@code stress-ng --hdd} for I/O load generation, {@code
 *       fallocate}/{@code dd} for disk fill. Works in any container.
 *   <li><strong>Syscall-level</strong> — {@code libchaos-io} LD_PRELOAD for per-file error
 *       injection (EIO, ENOSPC), latency, torn writes, and data corruption. Requires {@link
 *       SyscallFaultInjector#prepare} before container start.
 * </ul>
 *
 * <h2>Cross-distro compatibility</h2>
 *
 * <p>All shell commands are built via {@link DiskCommandBuilder} — an interface whose {@link
 * StressNgDiskCommandBuilder} implementation uses only POSIX-compatible tools ({@code /proc/comm},
 * {@code df -P}, {@code awk}) that work on both GNU coreutils (Debian/Ubuntu/RHEL) and BusyBox
 * (Alpine).
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * GenericContainer<?> container = new GenericContainer<>("redis:7.4");
 * SyscallFaultInjector.prepare(container);  // enables syscall-level chaos
 * container.start();
 *
 * DiskChaos chaos = new CgroupsDiskChaos();
 * chaos.stressDisk(container, 2);                                     // I/O stress
 * chaos.fillDisk(container, "/data", 80);                             // fill to 80%
 * chaos.injectIOError(container, "/data", "write", "EIO", 0.3);      // 30% write failures
 * chaos.injectIOLatency(container, "/data/wal.log", "fsync",
 *     Duration.ofMillis(200));                                        // 200ms fsync latency
 * chaos.injectTornWrite(container, "/data", 0.1);                     // 10% partial writes
 * chaos.injectCorruptRead(container, "/data", 0.05);                  // 5% corrupt reads
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CgroupsDiskChaos implements DiskChaos {

  /** Owner identifier for syscall rules — used in config file prefix and selective removal. */
  private static final String OWNER = "disk";

  /** Safety limit for fillDisk percentage to prevent completely full disk. */
  private static final int MAX_FILL_PERCENTAGE = 95;

  /** Pattern for safe mount point paths — prevents shell injection. */
  private static final Pattern SAFE_PATH = Pattern.compile("^[a-zA-Z0-9/_.-]+$");

  /** Pattern for valid size strings (e.g. "500M", "2G", "100K"). */
  private static final Pattern VALID_SIZE = Pattern.compile("^\\d+[KMG]$");

  private final DiskCommandBuilder commands;

  /** Creates a {@code CgroupsDiskChaos} using the default {@link StressNgDiskCommandBuilder}. */
  public CgroupsDiskChaos() {
    this(StressNgDiskCommandBuilder.INSTANCE);
  }

  /**
   * Creates a {@code CgroupsDiskChaos} with a custom {@link DiskCommandBuilder}.
   *
   * <p>Use this constructor in tests to inject a mock or alternative implementation.
   *
   * @param commands command builder (must not be null)
   */
  public CgroupsDiskChaos(final DiskCommandBuilder commands) {
    this.commands = Objects.requireNonNull(commands, "commands must not be null");
  }

  // ==================== Stress ====================

  @Override
  public void stressDisk(final GenericContainer<?> container, final int workers) {
    Objects.requireNonNull(container, "container must not be null");
    if (workers < 1) {
      throw new ChaosConfigurationException("workers must be >= 1, got: " + workers);
    }
    validateRunning(container);
    installTools(container);
    killStressNg(container);

    exec(container, commands.buildStressHddCommand(workers), "disk stress");
    log.info("Started disk stress: {} workers", workers);
  }

  @Override
  public void stressDisk(
      final GenericContainer<?> container, final int workers, final Duration duration) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
    if (workers < 1) {
      throw new ChaosConfigurationException("workers must be >= 1, got: " + workers);
    }
    if (duration.toSeconds() <= 0) {
      throw new ChaosConfigurationException("duration must be > 0, got: " + duration);
    }
    validateRunning(container);
    installTools(container);
    killStressNg(container);

    exec(
        container,
        commands.buildStressHddWithTimeoutCommand(workers, duration.toSeconds()),
        "disk stress with duration");
    log.info("Started disk stress: {} workers for {}s", workers, duration.toSeconds());
  }

  // ==================== Fill ====================

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
    validateRunning(container);

    try {
      final var dfResult =
          container.execInContainer(
              Shell.SH, Shell.FLAG_C, commands.buildGetDiskTotalKBCommand(mountPoint));
      if (dfResult.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to get disk size: " + dfResult.getStderr());
      }

      final long totalKB = Long.parseLong(dfResult.getStdout().trim());
      final long fillKB = (totalKB * percentage) / 100;
      final String loadFile = mountPoint + "/chaos-disk-load";

      exec(container, commands.buildFillDiskByCountKBCommand(loadFile, fillKB), "fill disk");
      log.info("Filled {} to {}% ({} KB)", mountPoint, percentage, fillKB);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to fill disk", e);
    }
  }

  @Override
  public void fillDiskBySize(
      final GenericContainer<?> container, final String mountPoint, final String size) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(mountPoint, "mountPoint must not be null");
    Objects.requireNonNull(size, "size must not be null");
    validatePath(mountPoint);
    validateSizeFormat(size);
    validateRunning(container);

    final String loadFile = mountPoint + "/chaos-disk-load";
    exec(
        container,
        commands.buildFillDiskBySizeCommand(loadFile, size, parseSizeMB(size)),
        "fill disk by size");
    log.info("Filled {} with {}", mountPoint, size);
  }

  // ==================== Syscall Fault Injection — lifecycle ====================

  @Override
  public void prepareForFaultInjection(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    SyscallFaultInjector.prepare(container);
  }

  @Override
  public void resetFaultInjection(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (SyscallFaultInjector.isActive(container)) {
      SyscallFaultInjector.removeRules(container, OWNER);
      log.info("Reset disk fault injection rules");
    }
  }

  @Override
  public boolean isFaultInjectionActive(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return SyscallFaultInjector.isActive(container);
  }

  // ==================== Syscall Fault Injection — effects ====================

  @Override
  public void injectIOError(
      final GenericContainer<?> container,
      final String path,
      final DiskOperation operation,
      final DiskErrno errno,
      final double probability) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(errno, "errno must not be null");
    validateRunning(container);

    SyscallFaultInjector.addRule(
        container,
        OWNER,
        SyscallRule.errno(path, operation.toLibchaosToken(), errno.toLibchaosToken(), probability)
            .build());
    log.info(
        "Injected IO error: {}:{} -> {} @ {}%", path, operation, errno, (int) (probability * 100));
  }

  @Override
  public void injectIOLatency(
      final GenericContainer<?> container,
      final String path,
      final DiskOperation operation,
      final Duration latency) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(latency, "latency must not be null");
    if (latency.isNegative()) {
      throw new ChaosConfigurationException("latency must not be negative: " + latency);
    }
    validateRunning(container);

    SyscallFaultInjector.addRule(
        container,
        OWNER,
        SyscallRule.latency(path, operation.toLibchaosToken(), latency.toMillis()).build());
    log.info("Injected IO latency: {}:{} -> {}ms", path, operation, latency.toMillis());
  }

  @Override
  public void injectTornWrite(
      final GenericContainer<?> container, final String path, final double probability) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(path, "path must not be null");
    validateRunning(container);

    SyscallFaultInjector.addRule(
        container,
        OWNER,
        SyscallRule.torn(path, DiskOperation.PWRITE.toLibchaosToken(), probability).build());
    log.info("Injected torn writes: {} @ {}%", path, (int) (probability * 100));
  }

  @Override
  public void injectCorruptRead(
      final GenericContainer<?> container, final String path, final double probability) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(path, "path must not be null");
    validateRunning(container);

    SyscallFaultInjector.addRule(
        container,
        OWNER,
        SyscallRule.corrupt(path, DiskOperation.READ.toLibchaosToken(), probability).build());
    log.info("Injected corrupt reads: {} @ {}%", path, (int) (probability * 100));
  }

  // ==================== Observability ====================

  @Override
  public int getDiskUsagePercent(final GenericContainer<?> container, final String mountPoint) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(mountPoint, "mountPoint must not be null");
    validateRunning(container);

    try {
      final var result =
          container.execInContainer(
              Shell.SH, Shell.FLAG_C, commands.buildGetDiskUsagePercentCommand(mountPoint));
      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("df failed: " + result.getStderr());
      }
      return Integer.parseInt(result.getStdout().trim());
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to read disk usage", e);
    }
  }

  @Override
  public boolean isStressed(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!container.isRunning()) return false;

    try {
      final var result =
          container.execInContainer(Shell.SH, Shell.FLAG_C, commands.buildIsStressedCommand());
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      return false;
    }
  }

  // ==================== Lifecycle ====================

  @Override
  public void installTools(final GenericContainer<?> container) {
    PackageInstaller.ensureInstalled(container, Tool.STRESS_NG);
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!container.isRunning()) return;

    killStressNg(container);

    // Remove disk fill files
    try {
      container.execInContainer(Shell.SH, Shell.FLAG_C, commands.buildRemoveFillFilesCommand());
    } catch (final Exception e) {
      log.warn("Failed to remove chaos-disk-load files: {}", e.getMessage());
    }

    resetFaultInjection(container);

    log.info("Reset disk chaos");
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  // ==================== Private ====================

  private void exec(final GenericContainer<?> container, final String command, final String label) {
    try {
      final var result = container.execInContainer(Shell.SH, Shell.FLAG_C, command);
      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to " + label + ": " + result.getStderr());
      }
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to " + label, e);
    }
  }

  private void killStressNg(final GenericContainer<?> container) {
    try {
      container.execInContainer(Shell.SH, Shell.FLAG_C, commands.buildKillStressNgCommand());
    } catch (final Exception ignored) {
      // no stress-ng running
    }
  }

  private static void validateRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }
  }

  private static void validatePath(final String path) {
    if (!SAFE_PATH.matcher(path).matches()) {
      throw new ChaosConfigurationException("Invalid path (unsafe characters): " + path);
    }
    if (path.contains("..")) {
      throw new ChaosConfigurationException("Path traversal not allowed: " + path);
    }
  }

  private static void validateSizeFormat(final String size) {
    if (!VALID_SIZE.matcher(size.toUpperCase()).matches()) {
      throw new ChaosConfigurationException(
          "Invalid size format (must be digits + K/M/G): " + size);
    }
  }

  private static int parseSizeMB(final String size) {
    final String upper = size.toUpperCase();
    final int value = Integer.parseInt(upper.substring(0, upper.length() - 1));
    return switch (upper.charAt(upper.length() - 1)) {
      case 'K' -> Math.max(1, value / 1024);
      case 'M' -> value;
      case 'G' -> value * 1024;
      default -> throw new ChaosConfigurationException("Invalid size suffix: " + size);
    };
  }
}
