/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk.command;

import java.util.Objects;

import com.macstab.chaos.core.command.disk.DiskCommandBuilder;
import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * {@link DiskCommandBuilder} implementation using {@code stress-ng --hdd}, {@code fallocate}/
 * {@code dd}, and {@code /proc/comm}-based process detection.
 *
 * <p>All commands work in any unprivileged Linux container without additional capabilities. No
 * cgroup writes, no {@code --privileged}, no {@code CAP_SYS_ADMIN} required.
 *
 * <p><strong>Required tools (auto-installed by {@link
 * com.macstab.chaos.core.util.PackageInstaller}):</strong>
 *
 * <ul>
 *   <li>{@code stress-ng} — {@code apt install stress-ng} / {@code apk add stress-ng}
 *   <li>{@code fallocate} — part of {@code util-linux} (present in most images; falls back to
 *       {@code dd} when absent or when the filesystem does not support extent allocation)
 * </ul>
 *
 * <p><strong>Cross-distro disk usage strategy:</strong>
 *
 * <p>GNU {@code df --output=pcent} fails on Alpine BusyBox with "unrecognized option". All disk
 * observability commands use POSIX {@code df -P} and {@code awk} to extract fields — guaranteed to
 * work on glibc (Debian/Ubuntu/RHEL) and musl/BusyBox (Alpine) containers.
 *
 * <p><strong>Zombie process filter:</strong>
 *
 * <p>After SIGKILL, stress-ng worker processes briefly remain as zombies (state {@code Z}) with
 * their {@code /proc/<pid>/comm} still readable. {@link #buildIsStressedCommand()} filters these
 * using {@code awk '{print $3}' /proc/<pid>/stat} so that {@code isStressed()} correctly returns
 * {@code false} after reset completes.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see DiskCommandBuilder
 */
public final class StressNgDiskCommandBuilder implements DiskCommandBuilder {

  private static final String STRESS_NG_COMM_PREFIX = "stress-ng";

  /** Shared singleton — all methods are pure functions with no state. */
  public static final StressNgDiskCommandBuilder INSTANCE = new StressNgDiskCommandBuilder();

  /** Creates a new {@code StressNgDiskCommandBuilder} instance. */
  public StressNgDiskCommandBuilder() {
    // Stateless — safe to instantiate or use as singleton.
  }

  // ==================== stress-ng HDD Commands ====================

  @Override
  public String buildStressHddCommand(final int workers) {
    validateWorkers(workers);
    return String.format("stress-ng --hdd %d --timeout 0 >/dev/null 2>&1 &", workers);
  }

  @Override
  public String buildStressHddWithTimeoutCommand(final int workers, final long seconds) {
    validateWorkers(workers);
    validateSeconds(seconds);
    return String.format("stress-ng --hdd %d --timeout %ds >/dev/null 2>&1 &", workers, seconds);
  }

  // ==================== Process Lifecycle ====================

  @Override
  public String buildIsStressedCommand() {
    // Prefix match on /proc/*/comm, skipping zombie processes (state Z in /proc/*/stat).
    // Identical zombie-filter logic as StressNgCommandBuilder#buildIsRunningByCommPrefixCommand.
    return String.format(
        "for f in $(grep -rl '^%s' /proc/[0-9]*/comm 2>/dev/null); do "
            + "p=${f%%%%/comm}; p=${p##*/}; "
            + "s=$(awk '{print $3}' /proc/$p/stat 2>/dev/null); "
            + "[ \"$s\" != \"Z\" ] && exit 0; "
            + "done; exit 1",
        STRESS_NG_COMM_PREFIX);
  }

  @Override
  public String buildKillStressNgCommand() {
    // Prefix match kills parent + all worker variants (stress-ng-hdd, stress-ng, etc.).
    // Idempotent: always exits 0 via trailing '; true'.
    return String.format(
        "for f in $(grep -rl '^%s' /proc/[0-9]*/comm 2>/dev/null); do "
            + "p=\"${f%%/comm}\"; p=\"${p##*/}\"; "
            + "kill -9 \"$p\" 2>/dev/null; "
            + "done; true",
        STRESS_NG_COMM_PREFIX);
  }

  // ==================== Disk Observability Commands ====================

  @Override
  public String buildGetDiskTotalKBCommand(final String mountPoint) {
    Objects.requireNonNull(mountPoint, "mountPoint must not be null");
    // df -P gives POSIX output (1K-blocks) on both GNU coreutils and BusyBox.
    // NR==2 skips the header line; $2 is the total-size column.
    return String.format("df -P %s | awk 'NR==2{print $2}'", mountPoint);
  }

  @Override
  public String buildGetDiskUsagePercentCommand(final String mountPoint) {
    Objects.requireNonNull(mountPoint, "mountPoint must not be null");
    // $5 is the "Capacity" column (e.g. "42%"); gsub strips the "%" character.
    return String.format("df -P %s | awk 'NR==2{gsub(/%%/,\"\",$5); print $5}'", mountPoint);
  }

  // ==================== Disk Fill Commands ====================

  @Override
  public String buildFillDiskByCountKBCommand(final String loadFile, final long countKB) {
    Objects.requireNonNull(loadFile, "loadFile must not be null");
    if (countKB < 1) {
      throw new ChaosConfigurationException("countKB must be >= 1, got: " + countKB);
    }
    // fallocate is near-instant (no real I/O) but unsupported on overlayfs/tmpfs.
    // dd fallback writes actual zero bytes — slower but universally supported.
    return String.format(
        "rm -f %s && fallocate -l %dK %s 2>/dev/null || dd if=/dev/zero of=%s bs=1K count=%d 2>&1",
        loadFile, countKB, loadFile, loadFile, countKB);
  }

  @Override
  public String buildFillDiskBySizeCommand(
      final String loadFile, final String size, final int sizeMb) {
    Objects.requireNonNull(loadFile, "loadFile must not be null");
    Objects.requireNonNull(size, "size must not be null");
    if (sizeMb < 1) {
      throw new ChaosConfigurationException("sizeMb must be >= 1, got: " + sizeMb);
    }
    return String.format(
        "rm -f %s && fallocate -l %s %s 2>/dev/null || dd if=/dev/zero of=%s bs=1M count=%d 2>&1",
        loadFile, size, loadFile, loadFile, sizeMb);
  }

  // ==================== Cleanup Commands ====================

  @Override
  public String buildRemoveFillFilesCommand() {
    return "find / -name 'chaos-disk-load' -delete 2>/dev/null || true";
  }

  // ==================== Private Validators ====================

  private static void validateWorkers(final int workers) {
    if (workers < 1) {
      throw new ChaosConfigurationException("workers must be >= 1, got: " + workers);
    }
  }

  private static void validateSeconds(final long seconds) {
    if (seconds < 1) {
      throw new IllegalArgumentException("seconds must be >= 1, got: " + seconds);
    }
  }
}
