/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Disk I/O chaos injection interface.
 *
 * <p>Provides three categories of disk chaos:
 *
 * <ul>
 *   <li><strong>Stress</strong> — generate heavy I/O load via {@code stress-ng}
 *   <li><strong>Fill</strong> — consume disk space to test ENOSPC handling
 *   <li><strong>Syscall fault injection</strong> — inject errors, latency, torn writes,
 *       and data corruption at the POSIX syscall level via {@code libchaos-io} LD_PRELOAD.
 *       These require {@link com.macstab.chaos.core.syscall.SyscallFaultInjector#prepare}
 *       to be called before container start.
 * </ul>
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-disk:1.0.0")
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface DiskChaos extends ChaosProvider {

  // ==================== Stress ====================

  /**
   * Inject disk I/O stress (heavy sequential read/write via stress-ng --hdd).
   *
   * @param container target container
   * @param workers   number of disk workers
   */
  void stressDisk(GenericContainer<?> container, int workers);

  /**
   * Inject disk I/O stress with automatic timeout.
   *
   * @param container target container
   * @param workers   number of disk workers
   * @param duration  auto-stop after this duration
   */
  void stressDisk(GenericContainer<?> container, int workers, Duration duration);

  // ==================== Fill ====================

  /**
   * Fill disk to percentage using {@code dd} or {@code fallocate}.
   *
   * @param container  target container
   * @param mountPoint disk mount point ("/data", "/")
   * @param percentage fill to this percentage (1-95)
   */
  void fillDisk(GenericContainer<?> container, String mountPoint, int percentage);

  /**
   * Fill disk by absolute size using {@code fallocate}.
   *
   * @param container  target container
   * @param mountPoint disk mount point
   * @param size       size string (e.g. "500M", "2G")
   */
  void fillDiskBySize(GenericContainer<?> container, String mountPoint, String size);

  // ==================== Syscall Fault Injection ====================

  /**
   * Inject I/O errors on disk operations at the syscall level.
   *
   * <p>Requires {@link com.macstab.chaos.core.syscall.SyscallFaultInjector#prepare}
   * before container start.
   *
   * @param container   target container
   * @param path        path prefix to match (e.g. "/data")
   * @param operation   syscall: "read", "write", "fsync", "fdatasync", "open", "close"
   * @param errno       error code: "EIO", "ENOSPC", "EDQUOT", "EROFS", "EACCES"
   * @param probability trigger probability 0.0-1.0
   */
  void injectIOError(
      GenericContainer<?> container, String path, String operation,
      String errno, double probability);

  /**
   * Inject latency on disk operations at the syscall level.
   *
   * @param container target container
   * @param path      path prefix to match
   * @param operation syscall name
   * @param latency   delay per operation
   */
  void injectIOLatency(
      GenericContainer<?> container, String path, String operation, Duration latency);

  /**
   * Inject torn (partial) writes — simulates power loss mid-write.
   *
   * @param container   target container
   * @param path        path prefix to match
   * @param probability trigger probability 0.0-1.0
   */
  void injectTornWrite(GenericContainer<?> container, String path, double probability);

  /**
   * Inject data corruption on reads — flips random bits in returned buffers.
   *
   * @param container   target container
   * @param path        path prefix to match
   * @param probability trigger probability 0.0-1.0
   */
  void injectCorruptRead(GenericContainer<?> container, String path, double probability);

  // ==================== Observability ====================

  /**
   * Get current disk usage percentage for a mount point.
   *
   * @param container  target container
   * @param mountPoint mount point to check
   * @return usage percentage 0-100
   */
  int getDiskUsagePercent(GenericContainer<?> container, String mountPoint);

  /**
   * Check if disk stress is currently active.
   *
   * @param container target container
   * @return true if stress-ng hdd workers are running
   */
  boolean isStressed(GenericContainer<?> container);
}
