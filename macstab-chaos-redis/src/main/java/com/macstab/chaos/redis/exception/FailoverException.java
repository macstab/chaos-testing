/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.exception;

import java.time.Duration;



/**
 * Exception thrown when failover operation fails or times out.
 *
 * <p><strong>Common Causes:</strong>
 *
 * <ul>
 *   <li>Insufficient quorum for failover
 *   <li>No viable replica to promote
 *   <li>Sentinel disagreement
 *   <li>Network partition during failover
 * </ul>
 *
 * <p><strong>Diagnostic Information:</strong>
 *
 * <ul>
 *   <li>Failover duration before timeout
 *   <li>Number of retries
 *   <li>Last known cluster state
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class FailoverException extends ClusterException {

  private final Duration timeout;
  private final Duration elapsed;
  private final int retryCount;

  /**
   * Creates a failover exception with timing context.
   *
   * @param message error description
   * @param timeout configured timeout
   * @param elapsed actual elapsed time
   * @param retryCount number of retries attempted
   */
  public FailoverException(
      final String message, final Duration timeout, final Duration elapsed, final int retryCount) {
    super(
        String.format(
            "%s. Timeout: %s, Elapsed: %s, Retries: %d", message, timeout, elapsed, retryCount));
    this.timeout = timeout;
    this.elapsed = elapsed;
    this.retryCount = retryCount;
  }

  /**
   * Creates a simple failover exception.
   *
   * @param message error description
   */
  public FailoverException(final String message) {
    super(message);
    this.timeout = null;
    this.elapsed = null;
    this.retryCount = 0;
  }

  /**
   * Returns the configured timeout.
   *
   * @return timeout duration, or null if not available
   */
  public Duration getTimeout() {
    return timeout;
  }

  /**
   * Returns the actual elapsed time.
   *
   * @return elapsed duration, or null if not available
   */
  public Duration getElapsed() {
    return elapsed;
  }

  /**
   * Returns the number of retries attempted.
   *
   * @return retry count
   */
  public int getRetryCount() {
    return retryCount;
  }
}
