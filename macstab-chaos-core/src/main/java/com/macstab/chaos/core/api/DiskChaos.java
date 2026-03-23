/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import org.testcontainers.containers.GenericContainer;

/**
 * Disk I/O chaos injection interface.
 *
 * <p>Provides I/O bandwidth limits, IOPS limits, and disk stress using cgroups v2 ({@code io.max})
 * and {@code stress-ng}.
 *
 * <p><strong>Real Implementation:</strong> Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-disk:1.0.0")
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface DiskChaos extends ChaosProvider {

  /**
   * Limit write bandwidth (bytes per second).
   *
   * <p>Uses cgroups v2 {@code io.max} (wbps=&lt;bytes&gt;).
   *
   * @param container target container
   * @param bytesPerSecond write limit (e.g., "10M", "1G")
   */
  void limitWriteBandwidth(GenericContainer<?> container, String bytesPerSecond);

  /**
   * Limit read bandwidth (bytes per second).
   *
   * @param container target container
   * @param bytesPerSecond read limit
   */
  void limitReadBandwidth(GenericContainer<?> container, String bytesPerSecond);

  /**
   * Limit read IOPS (operations per second).
   *
   * @param container target container
   * @param iops read IOPS limit
   */
  void limitReadIOPS(GenericContainer<?> container, int iops);

  /**
   * Limit write IOPS.
   *
   * @param container target container
   * @param iops write IOPS limit
   */
  void limitWriteIOPS(GenericContainer<?> container, int iops);

  /**
   * Fill disk to percentage (stress-ng --hdd).
   *
   * @param container target container
   * @param mountPoint disk mount point ("/data", "/")
   * @param percentage fill to this percentage (0-100)
   */
  void fillDisk(GenericContainer<?> container, String mountPoint, int percentage);

  /**
   * Inject disk I/O stress (heavy read/write).
   *
   * @param container target container
   * @param workers number of disk workers
   */
  void stressDisk(GenericContainer<?> container, int workers);
}
