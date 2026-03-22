/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.network.exception;

import com.macstab.chaos.core.exception.ClusterException;
import com.macstab.chaos.core.util.ContainerIdFormatter;

/**
 * Exception thrown when network chaos operations fail.
 *
 * <p><strong>Operations:</strong>
 *
 * <ul>
 *   <li>Latency injection
 *   <li>Packet loss injection
 *   <li>Jitter injection
 *   <li>Network partitioning
 *   <li>Chaos reset/cleanup
 * </ul>
 *
 * <p><strong>Common Causes:</strong>
 *
 * <ul>
 *   <li>Container missing NET_ADMIN capability
 *   <li>Container missing iproute2 package (tc command)
 *   <li>Invalid network interface
 *   <li>Invalid chaos parameters (negative duration, invalid percentage)
 * </ul>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * throw new NetworkChaosException(
 *     "injectLatency",
 *     containerId,
 *     "Container missing NET_ADMIN capability",
 *     cause);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public class NetworkChaosException extends ClusterException {

  private final String operation;
  private final String containerId;

  /**
   * Creates a network chaos exception with context.
   *
   * @param operation operation name (e.g., "injectLatency", "injectPacketLoss")
   * @param containerId container ID (first 12 chars)
   * @param message error description
   * @param cause underlying cause
   */
  public NetworkChaosException(
      final String operation,
      final String containerId,
      final String message,
      final Throwable cause) {
    super(
        String.format(
            "Network chaos operation '%s' failed for %s: %s",
            operation, ContainerIdFormatter.truncate(containerId), message),
        cause);
    this.operation = operation;
    this.containerId = containerId;
  }

  /**
   * Creates a network chaos exception without cause.
   *
   * @param operation operation name
   * @param containerId container ID
   * @param message error description
   */
  public NetworkChaosException(
      final String operation, final String containerId, final String message) {
    super(
        String.format(
            "Network chaos operation '%s' failed for %s: %s",
            operation, ContainerIdFormatter.truncate(containerId), message));
    this.operation = operation;
    this.containerId = containerId;
  }

  /**
   * Returns the operation name.
   *
   * @return operation (never null)
   */
  public String getOperation() {
    return operation;
  }

  /**
   * Returns the container ID.
   *
   * @return container ID (never null)
   */
  public String getContainerId() {
    return containerId;
  }
}
