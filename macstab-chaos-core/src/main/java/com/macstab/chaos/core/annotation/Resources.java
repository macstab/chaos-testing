/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Resource constraints for ANY container (universal).
 *
 * <p><strong>Purpose:</strong> Declaratively specify memory, CPU, and disk size limits for
 * Testcontainers. Works on any container annotation or field, not Redis-specific.
 *
 * <p><strong>Supported Constraints:</strong>
 *
 * <ul>
 *   <li><strong>Memory:</strong> {@code "512M"}, {@code "1G"}, {@code "2048K"}
 *       (case-insensitive)
 *   <li><strong>CPUs:</strong> {@code "2"}, {@code "0.5"}, {@code "4.0"} (decimal string)
 *   <li><strong>Disk:</strong> {@code "10G"}, {@code "5G"} (gigabytes only, Linux overlay2 only)
 * </ul>
 *
 * <p><strong>Why Resource Constraints Matter:</strong>
 *
 * <ul>
 *   <li><strong>Realistic testing:</strong> Production containers have limits, tests should match
 *   <li><strong>Chaos boundaries:</strong> CPU throttle at 90% means nothing if container has
 *       unlimited CPUs
 *   <li><strong>CI stability:</strong> Prevents test containers from exhausting host resources
 *   <li><strong>Deterministic behavior:</strong> Same limits = same performance characteristics
 * </ul>
 *
 * <p><strong>Basic Usage (Class-Level):</strong>
 *
 * <pre>{@code
 * @RedisStandalone
 * @Resources(memory="512M", cpus="2")
 * class RedisTest {
 *
 *   @Test
 *   void testMemoryChaos(RedisConnectionInfo info) {
 *     // Redis running with 512M memory limit, 2 CPU limit
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Platform Compatibility:</strong>
 *
 * <table border="1">
 *   <caption>Resource constraint support by platform</caption>
 *   <tr><th>Constraint</th><th>Linux</th><th>macOS</th><th>Windows</th></tr>
 *   <tr><td>Memory</td><td>✅ Full support</td><td>✅ Full support</td><td>✅ Full support</td></tr>
 *   <tr><td>CPUs</td><td>✅ Full support</td><td>✅ Full support</td><td>✅ Full support</td></tr>
 *   <tr><td>Disk</td><td>✅ Full support</td><td>⚠️ Logs warning</td><td>⚠️ Logs warning</td></tr>
 * </table>
 *
 * <p><strong>Default Behavior (Empty String):</strong>
 *
 * <ul>
 *   <li>{@code memory=""} → No memory limit (Docker default: unlimited)
 *   <li>{@code cpus=""} → No CPU limit (Docker default: unlimited)
 *   <li>{@code diskSize=""} → No disk limit (Docker default: unlimited)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see com.macstab.chaos.core.util.ResourceParser
 * @see ChaosTest
 */
@ChaosTest
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Resources {

  /**
   * Memory limit for the container.
   *
   * <p>Default: {@code ""} (no memory limit, unlimited)
   *
   * <p><strong>Format:</strong> Docker-compatible size string (case-insensitive)
   *
   * <ul>
   *   <li>{@code "512M"} - 512 megabytes
   *   <li>{@code "1G"} - 1 gigabyte
   *   <li>{@code "2048K"} - 2048 kilobytes (2MB)
   * </ul>
   *
   * @return memory limit (empty = no limit)
   * @see com.macstab.chaos.core.util.ResourceParser#parseMemoryBytes
   */
  String memory() default "";

  /**
   * CPU limit for the container.
   *
   * <p>Default: {@code ""} (no CPU limit, unlimited)
   *
   * <p><strong>Format:</strong> Decimal string representing CPU count
   *
   * <ul>
   *   <li>{@code "2"} - 2 CPUs
   *   <li>{@code "0.5"} - Half a CPU
   *   <li>{@code "4.0"} - 4 CPUs
   * </ul>
   *
   * @return CPU limit (empty = no limit)
   * @see com.macstab.chaos.core.util.ResourceParser#parseCpuNanoCpus
   */
  String cpus() default "";

  /**
   * Disk size limit for the container.
   *
   * <p>Default: {@code ""} (no disk size limit)
   *
   * <p><strong>Format:</strong> Gigabytes only (case-insensitive)
   *
   * <ul>
   *   <li>{@code "10G"} - 10 gigabytes
   *   <li>{@code "5G"} - 5 gigabytes
   * </ul>
   *
   * @return disk size limit (empty = no limit)
   * @see com.macstab.chaos.core.util.ResourceParser#parseDiskSizeOption
   */
  String diskSize() default "";
}
