/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.RedisStandalone;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ResourceBudget {

  /** Maximum number of Sentinel clusters per test class. */
  public static final int MAX_SENTINEL_CLUSTERS = 3;

  /** Maximum number of standalone Redis instances per test class. */
  public static final int MAX_STANDALONE_INSTANCES = 5;

  /** Maximum total containers across all Redis instances (Sentinel + standalone). */
  public static final int MAX_TOTAL_CONTAINERS = 20;

  /** Maximum estimated memory budget in megabytes. */
  public static final int MAX_MEMORY_MB = 100;

  /** Average memory per container in megabytes (Redis 7.4-alpine baseline). */
  private static final int MEMORY_PER_CONTAINER_MB = 4;

  private ResourceBudget() {
    throw new UnsupportedOperationException("Utility class - do not instantiate");
  }

  /**
   * Validates resource budget for Sentinel clusters.
   *
   * <p>Checks:
   *
   * <ol>
   *   <li>Cluster count ≤ {@link #MAX_SENTINEL_CLUSTERS}
   *   <li>Total containers ≤ {@link #MAX_TOTAL_CONTAINERS}
   *   <li>Estimated memory ≤ {@link #MAX_MEMORY_MB}
   * </ol>
   *
   * @param clusters Sentinel cluster annotations from test class
   * @throws ResourceBudgetExceededException if any limit exceeded
   * @throws IllegalArgumentException if clusters is null
   */
  public static void validateSentinelBudget(final RedisSentinel[] clusters) {
    if (clusters == null) {
      throw new IllegalArgumentException("clusters must not be null");
    }

    // Check 1: Cluster count
    if (clusters.length > MAX_SENTINEL_CLUSTERS) {
      throw new ResourceBudgetExceededException(
          String.format(
              "Too many Sentinel clusters: %d (max: %d). "
                  + "Each cluster uses 1 master + N replicas + M sentinels. "
                  + "Consider reducing cluster count or using standalone Redis for non-HA tests.",
              clusters.length, MAX_SENTINEL_CLUSTERS));
    }

    // Check 2: Total containers
    int totalContainers = 0;
    for (final RedisSentinel cluster : clusters) {
      totalContainers += 1; // master
      totalContainers += cluster.replicas();
      totalContainers += cluster.sentinels();
    }

    if (totalContainers > MAX_TOTAL_CONTAINERS) {
      throw new ResourceBudgetExceededException(
          String.format(
              "Total containers exceed limit: %d (max: %d). "
                  + "Reduce replicas/sentinels per cluster or use fewer clusters. "
                  + "Breakdown: %d cluster(s) with combined %d containers.",
              totalContainers, MAX_TOTAL_CONTAINERS, clusters.length, totalContainers));
    }

    // Check 3: Memory estimate
    final int estimatedMemoryMB = totalContainers * MEMORY_PER_CONTAINER_MB;
    if (estimatedMemoryMB > MAX_MEMORY_MB) {
      throw new ResourceBudgetExceededException(
          String.format(
              "Estimated memory usage: %dMB (max: %dMB). "
                  + "This may cause CI failures on memory-constrained runners. "
                  + "Calculation: %d containers × %dMB per container.",
              estimatedMemoryMB, MAX_MEMORY_MB, totalContainers, MEMORY_PER_CONTAINER_MB));
    }
  }

  /**
   * Validates resource budget for standalone Redis instances.
   *
   * <p>Checks:
   *
   * <ol>
   *   <li>Instance count ≤ {@link #MAX_STANDALONE_INSTANCES}
   *   <li>Total containers ≤ {@link #MAX_TOTAL_CONTAINERS}
   *   <li>Estimated memory ≤ {@link #MAX_MEMORY_MB}
   * </ol>
   *
   * @param instances standalone Redis annotations from test class
   * @throws ResourceBudgetExceededException if any limit exceeded
   * @throws IllegalArgumentException if instances is null
   */
  public static void validateStandaloneBudget(final RedisStandalone[] instances) {
    if (instances == null) {
      throw new IllegalArgumentException("instances must not be null");
    }

    // Check 1: Instance count
    if (instances.length > MAX_STANDALONE_INSTANCES) {
      throw new ResourceBudgetExceededException(
          String.format(
              "Too many standalone Redis instances: %d (max: %d). "
                  + "Standalone instances are lightweight (1 container each), "
                  + "but this limit prevents accidental misconfiguration.",
              instances.length, MAX_STANDALONE_INSTANCES));
    }

    // Check 2: Total containers (1 per standalone)
    final int totalContainers = instances.length;
    if (totalContainers > MAX_TOTAL_CONTAINERS) {
      throw new ResourceBudgetExceededException(
          String.format(
              "Total containers exceed limit: %d (max: %d).",
              totalContainers, MAX_TOTAL_CONTAINERS));
    }

    // Check 3: Memory estimate
    final int estimatedMemoryMB = totalContainers * MEMORY_PER_CONTAINER_MB;
    if (estimatedMemoryMB > MAX_MEMORY_MB) {
      throw new ResourceBudgetExceededException(
          String.format(
              "Estimated memory usage: %dMB (max: %dMB). "
                  + "Calculation: %d containers × %dMB per container.",
              estimatedMemoryMB, MAX_MEMORY_MB, totalContainers, MEMORY_PER_CONTAINER_MB));
    }
  }

  /**
   * Validates combined resource budget for mixed topologies (Sentinel + Standalone).
   *
   * <p>Use this when test class has both {@code @RedisSentinel} and {@code @RedisStandalone}
   * annotations.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @RedisSentinel(id = "ha-cluster", replicas = 2, sentinels = 3)  // 6 containers
   * @RedisStandalone(id = "cache")                                  // 1 container
   * @RedisStandalone(id = "session")                                // 1 container
   * class MixedTest { }  // Total: 8 containers, within budget
   * }</pre>
   *
   * @param sentinelClusters Sentinel cluster annotations (may be empty)
   * @param standaloneInstances standalone Redis annotations (may be empty)
   * @throws ResourceBudgetExceededException if combined limits exceeded
   * @throws IllegalArgumentException if either parameter is null
   */
  public static void validateMixedBudget(
      final RedisSentinel[] sentinelClusters, final RedisStandalone[] standaloneInstances) {

    if (sentinelClusters == null) {
      throw new IllegalArgumentException("sentinelClusters must not be null");
    }
    if (standaloneInstances == null) {
      throw new IllegalArgumentException("standaloneInstances must not be null");
    }

    // Calculate total containers
    int totalContainers = 0;

    // Count Sentinel containers
    for (final RedisSentinel cluster : sentinelClusters) {
      totalContainers += 1; // master
      totalContainers += cluster.replicas();
      totalContainers += cluster.sentinels();
    }

    // Count Standalone containers
    totalContainers += standaloneInstances.length;

    // Check total container limit
    if (totalContainers > MAX_TOTAL_CONTAINERS) {
      throw new ResourceBudgetExceededException(
          String.format(
              "Total containers exceed limit: %d (max: %d). "
                  + "Mixed topology: %d Sentinel cluster(s) + %d standalone instance(s). "
                  + "Reduce configuration or split into separate test classes.",
              totalContainers,
              MAX_TOTAL_CONTAINERS,
              sentinelClusters.length,
              standaloneInstances.length));
    }

    // Check memory limit
    final int estimatedMemoryMB = totalContainers * MEMORY_PER_CONTAINER_MB;
    if (estimatedMemoryMB > MAX_MEMORY_MB) {
      throw new ResourceBudgetExceededException(
          String.format(
              "Estimated memory usage: %dMB (max: %dMB). "
                  + "Mixed topology with %d total containers.",
              estimatedMemoryMB, MAX_MEMORY_MB, totalContainers));
    }
  }

  /**
   * Exception thrown when resource budget limits are exceeded.
   *
   * <p>This is a {@link RuntimeException} to fail fast during test initialization (before any
   * containers start). Prevents resource leaks and provides clear error messages.
   *
   * <p><strong>Error Message Format:</strong>
   *
   * <pre>
   * com.macstab.chaos.redis.util.ResourceBudget$ResourceBudgetExceededException:
   *   Too many Sentinel clusters: 4 (max: 3). Each cluster uses 1 master + N replicas +
   *   M sentinels. Consider reducing cluster count or using standalone Redis for non-HA tests.
   * </pre>
   */
  public static final class ResourceBudgetExceededException extends RuntimeException {

    /**
     * Creates exception with detailed error message.
     *
     * @param message human-readable explanation of budget violation (must not be null)
     */
    public ResourceBudgetExceededException(final String message) {
      super(message);
    }
  }
}
