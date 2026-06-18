/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.exception;

/**
 * Sealed base exception for all Redis cluster-related errors.
 *
 * <p><strong>Purpose:</strong> Provides a unified, exhaustive exception hierarchy for Redis cluster
 * operations, enabling precise error handling via pattern matching.
 *
 * <p><strong>Hierarchy (sealed):</strong>
 *
 * <pre>
 * ClusterException (sealed abstract)
 * ├─ ClusterCreationException   — Container startup failures
 * ├─ ClusterStartupException    — Multi-instance startup failures (with isolation)
 * ├─ ClusterTopologyException   — Topology inconsistencies (split-brain, missing master)
 * └─ FailoverException          — Failover operation failures (timeout, no quorum)
 * </pre>
 *
 * <p><strong>Pattern Matching Example:</strong>
 *
 * <pre>{@code
 * try {
 *   factory.createSentinelCluster();
 * } catch (ClusterException e) {
 *   String msg = switch (e) {
 *     case ClusterCreationException cce -> "Creation failed at " + cce.getComponentIndex();
 *     case ClusterStartupException cse -> "Startup failed: " + cse.getFailures().size() + " errors";
 *     case ClusterTopologyException cte -> "Topology: " + cte.getRoleDistribution();
 *     case FailoverException fe -> "Failover timed out after " + fe.getTimeout();
 *   };
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public abstract sealed class ClusterException extends RuntimeException
    permits ClusterCreationException,
        ClusterStartupException,
        ClusterTopologyException,
        FailoverException {

  /**
   * Creates a cluster exception with a message.
   *
   * @param message error description
   */
  protected ClusterException(final String message) {
    super(message);
  }

  /**
   * Creates a cluster exception with a message and cause.
   *
   * @param message error description
   * @param cause underlying cause
   */
  protected ClusterException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
