/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.exception;

import com.macstab.chaos.core.exception.ClusterException;

/**
 * Exception thrown when Redis Sentinel cluster creation fails.
 *
 * <p><strong>Common Causes:</strong>
 *
 * <ul>
 *   <li>Container startup timeout
 *   <li>Network configuration issues
 *   <li>Resource exhaustion (memory, disk, ports)
 *   <li>Docker daemon unavailable
 * </ul>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * throw new ClusterCreationException(
 *     "Failed to start replica 2 of 3",
 *     replicaIndex,
 *     totalReplicas,
 *     cause);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public class ClusterCreationException extends ClusterException {

  private final int componentIndex;
  private final int totalComponents;

  /**
   * Creates a cluster creation exception with detailed context.
   *
   * @param message error description
   * @param componentIndex index of component that failed (1-based)
   * @param totalComponents total number of components
   * @param cause underlying cause
   */
  public ClusterCreationException(
      final String message,
      final int componentIndex,
      final int totalComponents,
      final Throwable cause) {
    super(
        String.format(
            "%s (component %d/%d). Cause: %s",
            message, componentIndex, totalComponents, cause.getMessage()),
        cause);
    this.componentIndex = componentIndex;
    this.totalComponents = totalComponents;
  }

  /**
   * Creates a cluster creation exception without cause.
   *
   * @param message error description
   */
  public ClusterCreationException(final String message) {
    super(message);
    this.componentIndex = -1;
    this.totalComponents = -1;
  }

  /**
   * Returns the index of the component that failed.
   *
   * @return component index (1-based), or -1 if not applicable
   */
  public int getComponentIndex() {
    return componentIndex;
  }

  /**
   * Returns the total number of components.
   *
   * @return total components, or -1 if not applicable
   */
  public int getTotalComponents() {
    return totalComponents;
  }
}
