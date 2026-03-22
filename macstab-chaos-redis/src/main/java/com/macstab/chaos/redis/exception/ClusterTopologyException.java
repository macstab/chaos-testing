/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.exception;

import java.util.Map;

import com.macstab.chaos.core.exception.ClusterException;
import com.macstab.chaos.redis.control.role.ContainerRole;

/**
 * Exception thrown when cluster topology is inconsistent or unexpected.
 *
 * <p><strong>Common Scenarios:</strong>
 *
 * <ul>
 *   <li>No master found after failover
 *   <li>Insufficient replicas after startup
 *   <li>Missing sentinels
 *   <li>Split-brain (multiple masters)
 * </ul>
 *
 * <p><strong>Includes diagnostic information:</strong>
 *
 * <ul>
 *   <li>Running container count
 *   <li>Role distribution
 *   <li>Expected vs actual topology
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public class ClusterTopologyException extends ClusterException {

  private final int runningContainers;
  private final Map<ContainerRole, Integer> roleDistribution;

  /**
   * Creates a topology exception with diagnostic context.
   *
   * @param message error description
   * @param runningContainers number of running containers
   * @param roleDistribution role distribution map
   */
  public ClusterTopologyException(
      final String message,
      final int runningContainers,
      final Map<ContainerRole, Integer> roleDistribution) {
    super(formatMessage(message, runningContainers, roleDistribution));
    this.runningContainers = runningContainers;
    this.roleDistribution = Map.copyOf(roleDistribution);
  }

  /**
   * Creates a simple topology exception.
   *
   * @param message error description
   */
  public ClusterTopologyException(final String message) {
    super(message);
    this.runningContainers = -1;
    this.roleDistribution = Map.of();
  }

  /**
   * Returns the number of running containers.
   *
   * @return running container count, or -1 if not available
   */
  public int getRunningContainers() {
    return runningContainers;
  }

  /**
   * Returns the role distribution.
   *
   * @return immutable role distribution map (never null)
   */
  public Map<ContainerRole, Integer> getRoleDistribution() {
    return roleDistribution;
  }

  private static String formatMessage(
      final String message,
      final int runningContainers,
      final Map<ContainerRole, Integer> roleDistribution) {
    final StringBuilder sb = new StringBuilder(message);
    sb.append(". Cluster topology: ");
    sb.append(runningContainers).append(" running containers");

    if (!roleDistribution.isEmpty()) {
      sb.append(", roles: ");
      roleDistribution.forEach(
          (role, count) -> sb.append(role).append("=").append(count).append(" "));
    }

    sb.append(". This may indicate failover in progress or cluster misconfiguration.");
    return sb.toString();
  }
}
