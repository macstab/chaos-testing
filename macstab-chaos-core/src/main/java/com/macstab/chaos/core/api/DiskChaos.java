/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.syscall.DiskErrno;
import com.macstab.chaos.core.syscall.DiskOperation;

/**
 * Disk I/O chaos injection interface.
 *
 * <p>Provides three categories of disk chaos:
 *
 * <ul>
 *   <li><strong>Stress</strong> — generate heavy I/O load via {@code stress-ng}
 *   <li><strong>Fill</strong> — consume disk space to test ENOSPC handling
 *   <li><strong>Syscall fault injection</strong> — inject errors, latency, torn writes, and data
 *       corruption at the POSIX syscall level via {@code libchaos-io} LD_PRELOAD. Requires {@link
 *       #prepareForFaultInjection} before container start.
 * </ul>
 *
 * <h2>Syscall injection lifecycle</h2>
 *
 * <pre>{@code
 * DiskChaos chaos = new CgroupsDiskChaos();
 * chaos.prepareForFaultInjection(container);  // must be before container.start()
 * container.start();
 *
 * chaos.injectIOError(container, "/data", DiskOperation.WRITE, DiskErrno.EIO, 0.3);
 * chaos.injectTornWrite(container, "/data", 0.1);
 * chaos.resetFaultInjection(container);
 * }</pre>
 */
public interface DiskChaos extends ChaosProvider {

  // ==================== Stress ====================

  /**
   * Inject disk I/O stress (heavy sequential read/write via stress-ng --hdd).
   *
   * @param container target container
   * @param workers number of disk workers (>= 1)
   */
  void stressDisk(GenericContainer<?> container, int workers);

  /**
   * Inject disk I/O stress with automatic timeout.
   *
   * @param container target container
   * @param workers number of disk workers (>= 1)
   * @param duration auto-stop after this duration (> 0)
   */
  void stressDisk(GenericContainer<?> container, int workers, Duration duration);

  // ==================== Fill ====================

  /**
   * Fill disk to a percentage of total capacity.
   *
   * @param container target container
   * @param mountPoint disk mount point (e.g. "/data")
   * @param percentage fill target in range [1, 95]
   */
  void fillDisk(GenericContainer<?> container, String mountPoint, int percentage);

  /**
   * Fill disk by absolute size.
   *
   * @param container target container
   * @param mountPoint disk mount point
   * @param size size string, e.g. "500M", "2G"
   */
  void fillDiskBySize(GenericContainer<?> container, String mountPoint, String size);

  // ==================== Syscall Fault Injection — lifecycle ====================

  /**
   * Prepares the container for syscall-level fault injection.
   *
   * <p><strong>Must be called before {@code container.start()}.</strong> Copies the matching {@code
   * libchaos-io} binary into the container and sets {@code LD_PRELOAD}. Idempotent — safe to call
   * multiple times.
   *
   * @param container container to prepare (must not yet be started)
   */
  void prepareForFaultInjection(GenericContainer<?> container);

  /**
   * Removes all fault injection rules owned by the disk module. Does nothing if fault injection was
   * never prepared.
   *
   * @param container running container
   */
  void resetFaultInjection(GenericContainer<?> container);

  /**
   * Returns {@code true} if {@link #prepareForFaultInjection} was called on this container.
   *
   * @param container target container
   * @return {@code true} if the libchaos-io transport is active on this container
   */
  boolean isFaultInjectionActive(GenericContainer<?> container);

  // ==================== Syscall Fault Injection — effects ====================

  /**
   * Inject I/O errors on disk operations at the syscall level.
   *
   * <p>Requires {@link #prepareForFaultInjection} before container start.
   *
   * @param container target container (must be running)
   * @param path path prefix to match, e.g. "/data" or "*" for all paths
   * @param operation syscall to intercept
   * @param errno error code to return
   * @param probability trigger probability [0.0, 1.0]
   */
  void injectIOError(
      GenericContainer<?> container,
      String path,
      DiskOperation operation,
      DiskErrno errno,
      double probability);

  /**
   * Inject latency on disk operations at the syscall level.
   *
   * <p>Requires {@link #prepareForFaultInjection} before container start.
   *
   * @param container target container (must be running)
   * @param path path prefix to match
   * @param operation syscall to intercept
   * @param latency delay injected before the syscall executes
   */
  void injectIOLatency(
      GenericContainer<?> container, String path, DiskOperation operation, Duration latency);

  /**
   * Inject torn (partial) writes — simulates power loss mid-write.
   *
   * <p>Only valid on write-type operations ({@link DiskOperation#WRITE}, {@link
   * DiskOperation#PWRITE}). Requires {@link #prepareForFaultInjection} before start.
   *
   * @param container target container (must be running)
   * @param path path prefix to match
   * @param probability trigger probability [0.0, 1.0]
   */
  void injectTornWrite(GenericContainer<?> container, String path, double probability);

  /**
   * Inject data corruption on reads — flips random bits in the returned buffer.
   *
   * <p>Only valid on read-type operations ({@link DiskOperation#READ}, {@link
   * DiskOperation#PREAD}). Requires {@link #prepareForFaultInjection} before start.
   *
   * @param container target container (must be running)
   * @param path path prefix to match
   * @param probability trigger probability [0.0, 1.0]
   */
  void injectCorruptRead(GenericContainer<?> container, String path, double probability);

  // ==================== Observability ====================

  /**
   * Returns current disk usage percentage for the given mount point.
   *
   * @param container target container (must be running)
   * @param mountPoint mount point to check
   * @return usage percentage 0–100
   */
  int getDiskUsagePercent(GenericContainer<?> container, String mountPoint);

  /**
   * Returns {@code true} if disk stress workers are currently running.
   *
   * @param container target container
   * @return {@code true} if any stress-ng hdd workers are active
   */
  boolean isStressed(GenericContainer<?> container);
}
