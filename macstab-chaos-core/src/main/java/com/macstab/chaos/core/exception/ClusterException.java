/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

/**
 * Base exception for all cluster-related errors.
 *
 * <p><strong>Purpose:</strong> Provides a unified exception hierarchy for cluster operations,
 * enabling precise error handling and diagnostics.
 *
 * <p><strong>Common Subclasses:</strong>
 *
 * <ul>
 *   <li>ClusterCreationException - Cluster startup failures
 *   <li>ClusterTopologyException - Topology inconsistencies
 *   <li>FailoverException - Failover operation failures
 *   <li>{@link ContainerOperationException} - Container lifecycle failures
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public class ClusterException extends RuntimeException {

  /**
   * Creates a cluster exception with a message.
   *
   * @param message error description
   */
  public ClusterException(final String message) {
    super(message);
  }

  /**
   * Creates a cluster exception with a message and cause.
   *
   * @param message error description
   * @param cause underlying cause
   */
  public ClusterException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
