/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.failover;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.ContainerIdFormatter;
import com.macstab.chaos.redis.control.lifecycle.ContainerController;
import com.macstab.chaos.redis.control.role.RoleResolver;
import com.macstab.chaos.redis.exception.ClusterTopologyException;
import com.macstab.chaos.redis.exception.FailoverException;

import lombok.extern.slf4j.Slf4j;

/**
 * Drives failover simulation for Redis Sentinel clusters.
 *
 * <p><strong>Purpose:</strong> Tests Sentinel's automatic master re-election by killing the current
 * master and measuring the time until a new master is elected.
 *
 * <p><strong>Failover process:</strong>
 *
 * <ol>
 *   <li>Locate current master via {@link com.macstab.chaos.redis.control.role.RoleResolver}
 *   <li>Kill (SIGKILL) the master container
 *   <li>Poll until a different container reports {@code role:master}
 *   <li>Return elapsed time (failover duration)
 * </ol>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class FailoverHelper {
  private static final Duration DEFAULT_FAILOVER_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration RETRY_INTERVAL = Duration.ofMillis(500);

  private final ContainerController controller;
  private final RoleResolver roleResolver;
  private final List<GenericContainer<?>> allContainers;

  /**
   * Creates a failover helper.
   *
   * @param controller container lifecycle controller
   * @param roleResolver role resolver for container identification
   * @param allContainers all containers (master + replicas + sentinels)
   * @throws NullPointerException if any parameter is null
   */
  public FailoverHelper(
      final ContainerController controller,
      final RoleResolver roleResolver,
      final List<GenericContainer<?>> allContainers) {
    this.controller = Objects.requireNonNull(controller, "controller");
    this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver");
    this.allContainers = List.copyOf(Objects.requireNonNull(allContainers, "allContainers"));
  }

  /**
   * Triggers a failover by killing the master container.
   *
   * <p><strong>Algorithm:</strong>
   *
   * <ol>
   *   <li>Kill master (SIGKILL - simulate crash)
   *   <li>Wait for Sentinel to detect failure
   *   <li>Wait for new master promotion
   *   <li>Clear role cache (roles changed)
   *   <li>Return failover duration
   * </ol>
   *
   * @param masterContainer the master container to kill
   * @return failover duration (time until new master elected)
   * @throws NullPointerException if masterContainer is null
   * @throws IllegalStateException if failover doesn't complete within 30 seconds
   */
  public Duration triggerFailover(final GenericContainer<?> masterContainer) {
    return triggerFailover(masterContainer, DEFAULT_FAILOVER_TIMEOUT);
  }

  /**
   * Triggers a failover with custom timeout.
   *
   * @param masterContainer the master container to kill
   * @param timeout maximum wait time for failover completion
   * @return failover duration (time until new master elected)
   * @throws NullPointerException if masterContainer or timeout is null
   * @throws IllegalStateException if failover doesn't complete within timeout
   */
  public Duration triggerFailover(
      final GenericContainer<?> masterContainer, final Duration timeout) {
    Objects.requireNonNull(masterContainer, "masterContainer");
    Objects.requireNonNull(timeout, "timeout");

    final String masterId = ContainerIdFormatter.truncate(masterContainer.getContainerId());
    log.info("🔥 Triggering failover by killing master: {}", masterId);

    // Diagnostic logging - non-failing
    try {
      log.debug(
          "Pre-failover state: {} running containers, {} replicas available",
          countRunningContainers(),
          findReplicas().size());
    } catch (final Exception e) {
      log.debug("Unable to gather pre-failover diagnostics: {}", e.getMessage());
    }

    final long startTime = System.currentTimeMillis();

    // Kill master (simulate crash)
    controller.kill(masterContainer);
    log.debug("✓ Master killed: {}", masterId);

    // Wait for new master election (cache cleared in loop)
    waitForNewMaster(masterContainer, timeout);

    final long duration = System.currentTimeMillis() - startTime;
    final Duration failoverDuration = Duration.ofMillis(duration);

    final GenericContainer<?> newMaster = findMaster();
    log.info(
        "✓ Failover completed in {}ms. New master: {}",
        duration,
        ContainerIdFormatter.truncate(newMaster.getContainerId()));

    return failoverDuration;
  }

  /**
   * Finds the current master container.
   *
   * @return master container (never null)
   * @throws ClusterTopologyException if no master found
   */
  public GenericContainer<?> findMaster() {
    return allContainers.stream()
        .filter(GenericContainer::isRunning)
        .filter(container -> roleResolver.resolve(container).isMaster())
        .findFirst()
        .orElseThrow(
            () -> {
              final int runningCount = countRunningContainers();
              final var roleDistribution = calculateRoleDistribution();
              log.error(
                  "✗ No master found. Running containers: {}, Role distribution: {}",
                  runningCount,
                  roleDistribution);
              return new ClusterTopologyException(
                  "No master container found", runningCount, roleDistribution);
            });
  }

  private java.util.Map<com.macstab.chaos.redis.control.role.ContainerRole, Integer>
      calculateRoleDistribution() {
    return allContainers.stream()
        .filter(GenericContainer::isRunning)
        .map(roleResolver::resolve)
        .collect(
            java.util.stream.Collectors.groupingBy(
                java.util.function.Function.identity(),
                java.util.stream.Collectors.collectingAndThen(
                    java.util.stream.Collectors.counting(), Long::intValue)));
  }

  /**
   * Finds all replica containers.
   *
   * @return list of replica containers (never null, may be empty)
   */
  public List<GenericContainer<?>> findReplicas() {
    return allContainers.stream()
        .filter(GenericContainer::isRunning)
        .filter(container -> roleResolver.resolve(container).isReplica())
        .toList();
  }

  /**
   * Verifies that a new master has been elected (different from old master).
   *
   * @param oldMasterContainer the old master container
   * @return true if new master elected
   */
  public boolean isNewMasterElected(final GenericContainer<?> oldMasterContainer) {
    Objects.requireNonNull(oldMasterContainer, "oldMasterContainer");

    try {
      final GenericContainer<?> currentMaster = findMaster();
      return !currentMaster.equals(oldMasterContainer);
    } catch (ClusterTopologyException e) {
      // No master found yet - this is expected during failover
      return false;
    }
  }

  // ==================== Internal Helpers ====================

  /**
   * Waits for a new master to be elected.
   *
   * @param oldMasterContainer the old master container
   * @param timeout maximum wait time
   * @throws FailoverException if failover doesn't complete within timeout
   */
  private void waitForNewMaster(
      final GenericContainer<?> oldMasterContainer, final Duration timeout) {
    log.info(
        "⏱ Waiting for new master election (timeout: {}, check interval: {}ms)",
        timeout,
        RETRY_INTERVAL.toMillis());

    final long startTime = System.currentTimeMillis();
    final long timeoutMillis = timeout.toMillis();
    int retryCount = 0;

    while (System.currentTimeMillis() - startTime < timeoutMillis) {
      // Clear role cache on each iteration to detect role changes
      roleResolver.clearCache();
      retryCount++;

      if (retryCount % 10 == 0) {
        log.debug(
            "Still waiting for master election... ({} checks, {}ms elapsed)",
            retryCount,
            System.currentTimeMillis() - startTime);
      }

      if (isNewMasterElected(oldMasterContainer)) {
        final long elapsedMs = System.currentTimeMillis() - startTime;
        log.info("✓ New master elected after {}ms ({} checks)", elapsedMs, retryCount);
        return;
      }

      try {
        Thread.sleep(RETRY_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        final Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - startTime);
        throw new FailoverException(
            "Interrupted while waiting for failover", timeout, elapsed, retryCount);
      }
    }

    final Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - startTime);
    log.error(
        "✗ Failover timeout: no new master elected after {} ({} checks). "
            + "Old master: {}, Running containers: {}",
        elapsed,
        retryCount,
        ContainerIdFormatter.truncate(oldMasterContainer.getContainerId()),
        countRunningContainers());

    throw new FailoverException(
        "Failover did not complete: no new master elected", timeout, elapsed, retryCount);
  }

  private int countRunningContainers() {
    return (int) allContainers.stream().filter(GenericContainer::isRunning).count();
  }
}
