/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import org.testcontainers.containers.GenericContainer;

/**
 * Filesystem chaos injection interface.
 *
 * <p>I/O failures using FUSE overlay filesystem.
 *
 * <p><strong>Real Implementation:</strong> Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-filesystem:1.0.0")
 * }</pre>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * // Slow read operations on /app/data
 * chaos.filesystem().slowReads(container, "/app/data", Duration.ofMillis(500));
 *
 * // Fill disk with garbage data
 * chaos.filesystem().fillDisk(container, "500M");
 *
 * // Multiple chaos simultaneously
 * chaos.filesystem()
 *   .slowReads(container, "/app/logs", Duration.ofMillis(100))
 *   .corruptWrites(container, "/app/data", 0.01); // 1% corruption
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface FilesystemChaos extends ChaosProvider {

  /**
   * Fill disk with garbage data.
   *
   * <p>Creates large file to exhaust disk space.
   *
   * @param container target container
   * @param size size to fill (e.g., "500M", "1G")
   */
  void fillDisk(GenericContainer<?> container, String size);

  /**
   * Inject permission errors (EACCES).
   *
   * <p>Makes operations fail with "Permission denied" error.
   *
   * @param container target container
   * @param path target path
   * @param rate error rate (0.0-1.0)
   */
  void injectPermissionErrors(GenericContainer<?> container, String path, double rate);
}
