/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.util.Shell;
import com.macstab.chaos.cpu.command.StressNgCommandBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only observability queries for CPU chaos state.
 *
 * <p>Separated from {@link CgroupsCpuChaos} to keep mutation (inject chaos) and observation
 * (query state) in distinct classes. All methods are stateless — they read container state via
 * /proc and shell commands, never modify it.
 *
 * <p>Package-private — exposed only through the {@link com.macstab.chaos.core.api.CpuChaos}
 * interface methods delegated by {@link CgroupsCpuChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
final class CpuObservability {

  private static final long SAMPLE_INTERVAL_MS = 500L;
  private static final int IDLE_INDEX = 3;
  private static final int IOWAIT_INDEX = 4;

  private final StressNgCommandBuilder cmd;

  CpuObservability(final StressNgCommandBuilder cmd) {
    this.cmd = Objects.requireNonNull(cmd, "cmd must not be null");
  }

  // ==================== CPU Usage ====================

  /**
   * Two-sample /proc/stat delta over 500 ms.
   *
   * @return CPU usage percentage (0-100)
   */
  int getCurrentUsage(final GenericContainer<?> container) throws Exception {
    final long[] first = readCpuStat(container);
    Thread.sleep(SAMPLE_INTERVAL_MS);
    final long[] second = readCpuStat(container);
    return computeUsage(first, second);
  }

  // ==================== Core Count ====================

  int getAvailableCores(final GenericContainer<?> container) throws Exception {
    final var result = container.execInContainer(Shell.SH, Shell.FLAG_C,
        cmd.buildGetCoreCountCommand());
    if (result.getExitCode() == 0 && !result.getStdout().isBlank()) {
      return Integer.parseInt(result.getStdout().trim());
    }
    final var fallback = container.execInContainer(Shell.SH, Shell.FLAG_C,
        cmd.buildGetCoreCountFallbackCommand());
    if (fallback.getExitCode() == 0 && !fallback.getStdout().isBlank()) {
      return Integer.parseInt(fallback.getStdout().trim());
    }
    throw new ChaosOperationFailedException("Could not determine CPU core count");
  }

  /** Silent variant for use inside reset() - never throws. */
  int getAvailableCoresSilent(final GenericContainer<?> container) {
    try {
      return getAvailableCores(container);
    } catch (final Exception e) {
      return 1;
    }
  }

  // ==================== Process Detection ====================

  boolean isThrottled(final GenericContainer<?> container) {
    try {
      return container.execInContainer(Shell.SH, Shell.FLAG_C,
          cmd.buildIsRunningByCommExactCommand("cpulimit")).getExitCode() == 0;
    } catch (final Exception e) {
      return false;
    }
  }

  boolean isStressed(final GenericContainer<?> container) {
    try {
      return container.execInContainer(Shell.SH, Shell.FLAG_C,
          cmd.buildIsRunningByCommPrefixCommand("stress-ng")).getExitCode() == 0;
    } catch (final Exception e) {
      return false;
    }
  }

  // ==================== Affinity ====================

  boolean isAffinityPinned(final GenericContainer<?> container) {
    try {
      final long currentMask = readAffinityMask(container);
      final int cores = getAvailableCores(container);
      return currentMask != computeFullMask(cores);
    } catch (final Exception e) {
      return false;
    }
  }

  /**
   * Computes the affinity bitmask representing all available cores.
   *
   * <p>For {@code cores < 64}: {@code (1L << cores) - 1} — e.g., 12 cores → {@code 0xfff}.
   * For {@code cores >= 64}: {@code -1L} ({@code 0xffff...ffff}) — all bits set, avoids
   * {@code 1L << 64} overflow (Java shifts are mod 64).
   *
   * <p>Package-private for unit testing.
   *
   * @param cores number of available CPU cores (must be ≥ 1)
   * @return full-mask long with one bit per core, all set
   */
  static long computeFullMask(final int cores) {
    return cores >= Long.SIZE ? -1L : (1L << cores) - 1;
  }

  long readAffinityMask(final GenericContainer<?> container) {
    try {
      final var result = container.execInContainer(Shell.SH, Shell.FLAG_C,
          cmd.buildGetAffinityMaskCommand(1));
      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("taskset -p 1 failed: " + result.getStderr());
      }
      // Output: "pid 1's current affinity mask: fff"
      final String output = result.getStdout().trim();
      final String hexMask = output.substring(output.lastIndexOf(' ') + 1);
      return Long.parseLong(hexMask, 16);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to read affinity mask", e);
    }
  }

  // ==================== Nice Value ====================

  int getNiceValue(final GenericContainer<?> container) throws Exception {
    final var result = container.execInContainer(Shell.SH, Shell.FLAG_C,
        cmd.buildGetNiceValueCommand(1));
    if (result.getExitCode() != 0 || result.getStdout().isBlank()) {
      throw new ChaosOperationFailedException(
          "Failed to read nice value from /proc/1/stat: " + result.getStderr());
    }
    return Integer.parseInt(result.getStdout().trim());
  }

  // ==================== /proc/stat Parsing ====================

  private long[] readCpuStat(final GenericContainer<?> container) throws Exception {
    final var result = container.execInContainer(Shell.SH, Shell.FLAG_C,
        cmd.buildReadCpuStatCommand());
    if (result.getExitCode() != 0) {
      throw new ChaosOperationFailedException("Failed to read /proc/stat: " + result.getStderr());
    }
    final String firstLine = result.getStdout().lines().findFirst()
        .orElseThrow(() -> new ChaosOperationFailedException("Empty /proc/stat"));
    return parseCpuStat(firstLine);
  }

  /**
   * Parses the aggregate cpu line from /proc/stat into tick counters.
   * Package-private for unit testing.
   */
  static long[] parseCpuStat(final String procStatLine) {
    final String[] parts = procStatLine.trim().split("\\s+");
    final long[] values = new long[parts.length - 1];
    for (int i = 0; i < values.length; i++) {
      values[i] = Long.parseLong(parts[i + 1]);
    }
    return values;
  }

  /**
   * Computes CPU busy percentage from two /proc/stat samples.
   * Package-private for unit testing.
   */
  static int computeUsage(final long[] first, final long[] second) {
    long totalDelta = 0;
    final int len = Math.min(first.length, second.length);
    for (int i = 0; i < len; i++) {
      totalDelta += second[i] - first[i];
    }
    if (totalDelta <= 0) {
      return 0;
    }
    final long idleDelta =
        (second[IDLE_INDEX] - first[IDLE_INDEX]) + (second[IOWAIT_INDEX] - first[IOWAIT_INDEX]);
    return (int) ((totalDelta - idleDelta) * 100L / totalDelta);
  }
}
