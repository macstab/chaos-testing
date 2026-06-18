/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.disk;

/**
 * Platform-specific disk chaos command builder.
 *
 * <p>Builds shell command strings for disk I/O stress, disk fill, and process lifecycle management.
 * All methods return pure shell command strings — no I/O, no execution, no side effects. Command
 * construction is completely separated from execution.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@code StressNgDiskCommandBuilder} — {@code stress-ng --hdd}, {@code fallocate}/{@code dd},
 *       and {@code /proc/comm}-based process detection; works in any unprivileged Linux container.
 * </ul>
 *
 * <p><strong>Process detection strategy:</strong>
 *
 * <p>All process lifecycle commands use {@code /proc/comm} exclusively — no dependency on {@code
 * pgrep}, {@code pkill}, or {@code ps}, which may be absent in minimal container images (e.g.,
 * {@code redis:7.4}).
 *
 * <p><strong>Cross-distro compatibility:</strong>
 *
 * <p>Disk usage commands use POSIX {@code df -P} output format, which works on both GNU coreutils
 * (Debian/Ubuntu/RHEL) and BusyBox (Alpine). GNU-only flags such as {@code --output=pcent} are
 * explicitly avoided.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface DiskCommandBuilder {

  // ==================== stress-ng HDD Commands ====================

  /**
   * Build command to start disk I/O stress workers that run until explicitly killed.
   *
   * <p>Runs {@code stress-ng --hdd <workers> --timeout 0} in the background. Each worker performs
   * sequential read/write loops, saturating disk bandwidth.
   *
   * @param workers number of HDD worker processes (must be ≥ 1)
   * @return shell command string
   */
  String buildStressHddCommand(int workers);

  /**
   * Build command to start disk I/O stress workers that auto-terminate after {@code seconds}.
   *
   * @param workers number of HDD worker processes (must be ≥ 1)
   * @param seconds stress duration in seconds (must be ≥ 1)
   * @return shell command string
   */
  String buildStressHddWithTimeoutCommand(int workers, long seconds);

  // ==================== Process Lifecycle (via /proc/comm) ====================

  /**
   * Build command to test whether any non-zombie {@code stress-ng} process is currently running.
   *
   * <p>Uses {@code /proc/[0-9]* /comm} prefix matching for {@code "stress-ng"} and filters zombie
   * processes by checking the state field in {@code /proc/<pid>/stat}. Exits {@code 0} if at least
   * one live (non-zombie) stress-ng process exists, non-zero otherwise.
   *
   * <p><strong>Zombie filter is critical:</strong> After {@code SIGKILL}, stress-ng worker
   * processes may briefly remain as zombies (state Z) with their {@code comm} file still readable.
   * Without zombie filtering, {@code isStressed()} returns {@code true} even after reset completes.
   *
   * @return shell command string
   */
  String buildIsStressedCommand();

  /**
   * Build command to send SIGKILL to all {@code stress-ng} processes (parent + all workers).
   *
   * <p>Uses {@code /proc/comm} prefix matching to cover all worker variants ({@code stress-ng},
   * {@code stress-ng-hdd}, etc.). Idempotent — always exits {@code 0}.
   *
   * @return shell command string
   */
  String buildKillStressNgCommand();

  // ==================== Disk Observability Commands ====================

  /**
   * Build command to retrieve the total disk size in 1K-blocks for {@code mountPoint}.
   *
   * <p>Uses POSIX {@code df -P} output format — compatible with both GNU coreutils and BusyBox
   * {@code df}. Output is a single decimal integer (total 1K-blocks).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * buildGetDiskTotalKBCommand("/data")
   * → "df -P /data | awk 'NR==2{print $2}'"
   * → stdout: "61202244"
   * </pre>
   *
   * @param mountPoint mount point path
   * @return shell command string
   */
  String buildGetDiskTotalKBCommand(String mountPoint);

  /**
   * Build command to retrieve the current disk usage percentage for {@code mountPoint}.
   *
   * <p>Uses POSIX {@code df -P} — avoids GNU-only {@code --output=pcent} which fails on Alpine
   * BusyBox. Output is a single integer (no {@code %} sign).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * buildGetDiskUsagePercentCommand("/data")
   * → "df -P /data | awk 'NR==2{gsub(/%/,\"\",$5); print $5}'"
   * → stdout: "42"
   * </pre>
   *
   * @param mountPoint mount point path
   * @return shell command string
   */
  String buildGetDiskUsagePercentCommand(String mountPoint);

  // ==================== Disk Fill Commands ====================

  /**
   * Build command to fill disk at {@code loadFile} by writing exactly {@code countKB} kilobytes.
   *
   * <p>Attempts {@code fallocate} first (near-instant, no actual I/O) and falls back to {@code dd}
   * for filesystems that do not support {@code fallocate} (e.g., overlayfs, tmpfs).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * buildFillDiskByCountKBCommand("/tmp/chaos-disk-load", 102400)
   * → "rm -f /tmp/chaos-disk-load && fallocate -l 102400K /tmp/chaos-disk-load 2>/dev/null
   *     || dd if=/dev/zero of=/tmp/chaos-disk-load bs=1K count=102400 2>&1"
   * }</pre>
   *
   * @param loadFile absolute path of the fill file (must be validated by caller)
   * @param countKB number of 1K blocks to write (must be ≥ 1)
   * @return shell command string
   */
  String buildFillDiskByCountKBCommand(String loadFile, long countKB);

  /**
   * Build command to fill disk at {@code loadFile} to an absolute {@code size}.
   *
   * <p>Attempts {@code fallocate} first (near-instant), falls back to {@code dd} writing {@code
   * sizeMb} megabytes.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * buildFillDiskBySizeCommand("/tmp/chaos-disk-load", "500M", 500)
   * → "rm -f /tmp/chaos-disk-load && fallocate -l 500M /tmp/chaos-disk-load 2>/dev/null
   *     || dd if=/dev/zero of=/tmp/chaos-disk-load bs=1M count=500 2>&1"
   * }</pre>
   *
   * @param loadFile absolute path of the fill file (must be validated by caller)
   * @param size size string accepted by {@code fallocate -l} (e.g., {@code "500M"})
   * @param sizeMb fallback size in MB for {@code dd} (must be ≥ 1)
   * @return shell command string
   */
  String buildFillDiskBySizeCommand(String loadFile, String size, int sizeMb);

  // ==================== Cleanup Commands ====================

  /**
   * Build command to remove all {@code chaos-disk-load} files from the container filesystem.
   *
   * <p>Scans the entire filesystem tree via {@code find} and deletes every match. Safe to call when
   * no fill files exist — always exits {@code 0}.
   *
   * @return shell command string
   */
  String buildRemoveFillFilesCommand();
}
