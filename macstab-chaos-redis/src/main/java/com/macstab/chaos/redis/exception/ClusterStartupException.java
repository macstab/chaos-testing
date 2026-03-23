/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.exception;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ClusterStartupException extends RuntimeException {

  private final List<ClusterStartupFailure> failures;
  private final List<String> cleanupActions;

  /**
   * Creates exception with cluster startup failures and cleanup actions.
   *
   * @param failures list of cluster startup failures (must not be null or empty)
   * @param cleanupActions list of cleanup actions performed (may be empty)
   */
  public ClusterStartupException(
      final List<ClusterStartupFailure> failures, final List<String> cleanupActions) {

    super(buildMessage(failures, cleanupActions));

    if (failures == null || failures.isEmpty()) {
      throw new IllegalArgumentException("failures must not be null or empty");
    }

    this.failures = List.copyOf(failures);
    this.cleanupActions = cleanupActions != null ? List.copyOf(cleanupActions) : List.of();
  }

  /**
   * Gets the list of cluster startup failures.
   *
   * @return unmodifiable list of failures (never null or empty)
   */
  public List<ClusterStartupFailure> getFailures() {
    return failures;
  }

  /**
   * Gets the list of cleanup actions performed.
   *
   * @return unmodifiable list of cleanup actions (never null, may be empty)
   */
  public List<String> getCleanupActions() {
    return cleanupActions;
  }

  /**
   * Builds detailed error message from failures and cleanup actions.
   *
   * @param failures cluster startup failures
   * @param cleanupActions cleanup actions performed
   * @return formatted error message
   */
  private static String buildMessage(
      final List<ClusterStartupFailure> failures, final List<String> cleanupActions) {

    final StringBuilder msg = new StringBuilder();

    // Header
    msg.append("Failed to start ").append(failures.size()).append(" cluster(s):\n");

    // Failure details
    for (final ClusterStartupFailure failure : failures) {
      msg.append("  - Cluster '")
          .append(failure.getClusterId())
          .append("': ")
          .append(failure.getErrorMessage())
          .append("\n");
    }

    // Isolation guarantee
    msg.append(
        "\nAll successfully started clusters have been cleaned up to prevent resource leaks.\n");

    // Cleanup summary
    if (cleanupActions != null && !cleanupActions.isEmpty()) {
      msg.append("Cleanup summary:\n");
      for (final String action : cleanupActions) {
        msg.append("  - ").append(action).append("\n");
      }
    }

    return msg.toString();
  }

  /**
   * Represents a single cluster startup failure.
   *
   * <p>Immutable value object holding failure details for diagnostic purposes.
   */
  public static final class ClusterStartupFailure {
    private final String clusterId;
    private final String errorMessage;
    private final Throwable cause;

    /**
     * Creates a cluster startup failure.
     *
     * @param clusterId cluster ID from annotation (must not be null or empty)
     * @param errorMessage human-readable error description (must not be null)
     * @param cause original exception (may be null)
     */
    public ClusterStartupFailure(
        final String clusterId, final String errorMessage, final Throwable cause) {

      if (clusterId == null || clusterId.isEmpty()) {
        throw new IllegalArgumentException("clusterId must not be null or empty");
      }
      if (errorMessage == null) {
        throw new IllegalArgumentException("errorMessage must not be null");
      }

      this.clusterId = clusterId;
      this.errorMessage = errorMessage;
      this.cause = cause;
    }

    /**
     * Gets the cluster ID.
     *
     * @return cluster ID from {@code @RedisSentinel(id = "...")} or {@code @RedisStandalone(id =
     *     "...")}
     */
    public String getClusterId() {
      return clusterId;
    }

    /**
     * Gets the error message.
     *
     * @return human-readable error description (never null)
     */
    public String getErrorMessage() {
      return errorMessage;
    }

    /**
     * Gets the original exception that caused the failure.
     *
     * @return original exception (may be null if failure was not exception-based)
     */
    public Throwable getCause() {
      return cause;
    }

    @Override
    public String toString() {
      return String.format(
          "ClusterStartupFailure[clusterId='%s', error='%s']", clusterId, errorMessage);
    }
  }
}
