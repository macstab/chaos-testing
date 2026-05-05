/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import org.testcontainers.containers.GenericContainer;

/**
 * Filesystem-level chaos using disk space exhaustion and permission errors.
 *
 * <p>Simulates disk full scenarios and permission failures to test application resilience.
 *
 * <h2>Complete Example: Disk Full Handling</h2>
 *
 * <pre>{@code
 * @Test
 * void shouldHandleDiskFull() {
 *   FilesystemChaos chaos = new FilesystemChaosProvider();
 *
 *   // Fill disk to 95% (leave 50MB free)
 *   chaos.fillDisk(app, "950M");  // 1GB disk → 50MB free
 *
 *   // Application should detect and handle gracefully
 *   assertThatThrownBy(() -> app.writeLog("data"))
 *       .isInstanceOf(IOException.class)
 *       .hasMessageContaining("No space left");
 *
 *   // Verify: Application stopped writing, logged error
 *   assertThat(app.getMetrics().getDiskFullErrors()).isGreaterThan(0);
 * }
 * }</pre>
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li>Disk full scenarios (log rotation, temp file cleanup)
 *   <li>Permission errors (file access denied)
 *   <li>Quota exhaustion testing
 * </ul>
 *
 * <h2>Implementation</h2>
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-filesystem:1.0.0")
 * FilesystemChaos chaos = new FilesystemChaosProvider();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface FilesystemChaos extends ChaosProvider {

  /**
   * Fill disk with garbage data to simulate disk full.
   *
   * <p><strong>Example: Test Disk Full Recovery</strong>
   *
   * <pre>{@code
   * @Test
   * void shouldCleanupOldLogsWhenDiskFull() {
   *   chaos.fillDisk(app, "900M");  // 1GB disk → 100MB free
   *
   *   app.writeLog("data");  // Should trigger cleanup
   *
   *   // Verify: Old logs deleted automatically
   *   assertThat(app.getOldLogFiles()).isEmpty();
   * }
   * }</pre>
   *
   * @param container target container
   * @param size size to fill (e.g., "500M", "1G")
   */
  void fillDisk(GenericContainer<?> container, String size);

  /**
   * Inject permission errors (EACCES).
   *
   * <p><strong>Example: Test Permission Denied Handling</strong>
   *
   * <pre>{@code
   * @Test
   * void shouldHandlePermissionDenied() {
   *   chaos.injectPermissionErrors(app, "/data", 1.0);  // 100% fail
   *
   *   assertThatThrownBy(() -> app.writeData("test"))
   *       .isInstanceOf(AccessDeniedException.class);
   * }
   * }</pre>
   *
   * @param container target container
   * @param path target path
   * @param rate error rate (0.0-1.0, e.g., 0.1 = 10% of operations fail)
   */
  void injectPermissionErrors(GenericContainer<?> container, String path, double rate);
}
