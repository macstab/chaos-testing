/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configure container resource limits (validation-only).
 *
 * <p>This annotation validates container resource limits. It does NOT automatically apply limits
 * (Docker limitation: resource limits must be set at container creation time).
 *
 * <p><strong>Behavior:</strong> Validates limits and fails test with clear error message if limits
 * are missing or insufficient.
 *
 * <p><strong>Why resource limits matter for chaos testing:</strong>
 *
 * <ul>
 *   <li>Memory chaos: Cannot trigger OOM without memory limit
 *   <li>CPU chaos: Cannot observe throttling without CPU limit
 *   <li>Disk chaos: Cannot fill disk without disk limit
 * </ul>
 *
 * <p><strong>Field-level usage (applies to specific container):</strong>
 *
 * <pre>{@code
 * @RedisStandalone
 * @ConfigureContainer(memory = "512M", cpus = 2)
 * GenericContainer<?> redis;
 * }</pre>
 *
 * <p><strong>How to set resource limits:</strong>
 *
 * <pre>{@code
 * @RedisStandalone
 * GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
 *     .withCreateContainerCmdModifier(cmd ->
 *         cmd.getHostConfig()
 *             .withMemory(512L * 1024 * 1024)  // 512MB
 *             .withCpuCount(2L));
 * }</pre>
 *
 * <p><strong>Error message example:</strong>
 *
 * <pre>
 * Container 'redis' requires memory limit of 512M for memory chaos testing.
 * Add: .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(536870912L))
 * </pre>
 *
 * <p><strong>Note:</strong> This annotation is FIELD-only (resource limits are per-container, not
 * per-test-class).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigureContainer {

  /**
   * Memory limit (e.g., "512M", "1G").
   *
   * <p><strong>Format:</strong> Number + unit (K, M, G)
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>"512M" = 512 megabytes
   *   <li>"1G" = 1 gigabyte
   *   <li>"2048M" = 2 gigabytes
   * </ul>
   *
   * <p><strong>Validation:</strong> Fails if container memory limit is less than specified value.
   *
   * @return memory limit (empty = no validation)
   */
  String memory() default "";

  /**
   * CPU count limit.
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>1 = single CPU
   *   <li>2 = dual CPU
   *   <li>4 = quad CPU
   * </ul>
   *
   * <p><strong>Validation:</strong> Fails if container CPU count is less than specified value.
   *
   * @return CPU count (-1 = no validation)
   */
  int cpus() default -1;

  /**
   * Disk size limit (e.g., "10G", "20G").
   *
   * <p><strong>Format:</strong> Number + unit (G, T)
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>"10G" = 10 gigabytes
   *   <li>"1T" = 1 terabyte
   * </ul>
   *
   * <p><strong>Note:</strong> Requires Docker storage driver support (overlay2, devicemapper).
   *
   * <p><strong>Validation:</strong> Fails if container disk limit is less than specified value.
   *
   * @return disk size (empty = no validation)
   */
  String diskSize() default "";

  /**
   * CPU shares (relative weight, 0-1024).
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>512 = half weight
   *   <li>1024 = full weight
   *   <li>2048 = double weight
   * </ul>
   *
   * <p><strong>Note:</strong> CPU shares are relative, not absolute. A container with 512 shares
   * gets half the CPU time of a container with 1024 shares when both are competing.
   *
   * <p><strong>Validation:</strong> Fails if container CPU shares are less than specified value.
   *
   * @return CPU shares (-1 = no validation)
   */
  int cpuShares() default -1;

  /**
   * Memory + swap limit (e.g., "1G").
   *
   * <p><strong>Format:</strong> Number + unit (K, M, G)
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>"1G" = 1 gigabyte (memory + swap combined)
   *   <li>"-1" = unlimited swap
   * </ul>
   *
   * <p><strong>Note:</strong> {@code memorySwap} is the TOTAL limit (memory + swap), not just swap.
   *
   * <p><strong>Validation:</strong> Fails if container swap limit is less than specified value.
   *
   * @return memory swap limit (empty = no validation)
   */
  String memorySwap() default "";
}
