/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

import com.macstab.chaos.core.util.ContainerIdFormatter;

/**
 * Exception thrown when container lifecycle operations fail.
 *
 * <p><strong>Operations:</strong>
 *
 * <ul>
 *   <li>Restart
 *   <li>Kill
 *   <li>Pause
 *   <li>Resume
 *   <li>Health check
 * </ul>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * throw new ContainerOperationException(
 *     "restart",
 *     containerId,
 *     "Container failed to start after restart",
 *     cause);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public class ContainerOperationException extends ClusterException {

  /** Operation name (e.g., "restart", "kill"). */
  private final String operation;

  /** Container ID (first 12 characters). */
  private final String containerId;

  /**
   * Creates a container operation exception with context.
   *
   * @param operation operation name (e.g., "restart", "kill")
   * @param containerId container ID (first 12 chars)
   * @param message error description
   * @param cause underlying cause
   */
  public ContainerOperationException(
      final String operation,
      final String containerId,
      final String message,
      final Throwable cause) {
    super(
        String.format(
            "Container operation '%s' failed for %s: %s",
            operation, ContainerIdFormatter.truncate(containerId), message),
        cause);
    this.operation = operation;
    this.containerId = containerId;
  }

  /**
   * Creates a container operation exception without cause.
   *
   * @param operation operation name
   * @param containerId container ID
   * @param message error description
   */
  public ContainerOperationException(
      final String operation, final String containerId, final String message) {
    super(
        String.format(
            "Container operation '%s' failed for %s: %s",
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
