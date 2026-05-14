/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

/**
 * Policy controlling how {@link PatternExecutor} reacts when a {@link ValueConsumer} throws.
 *
 * <p>The policy is checked against the running count of <strong>consecutive</strong> failures:
 * once a sample succeeds the counter resets. A pattern aborts when the consecutive failure count
 * exceeds {@link #maxConsecutiveFailures()}.
 *
 * <p>Use the factory constants / methods rather than the canonical constructor:
 *
 * <ul>
 *   <li>{@link #ABORT} — fail immediately on the first error (current default behaviour)
 *   <li>{@link #IGNORE} — log and continue regardless of how many errors occur
 *   <li>{@link #stopAfter(int)} — continue while consecutive failures &lt;= {@code n}
 * </ul>
 *
 * @param maxConsecutiveFailures threshold; {@code 0} aborts on the first failure, {@link
 *     Integer#MAX_VALUE} never aborts
 * @author Christian Schnapka - Macstab GmbH
 */
public record FailurePolicy(int maxConsecutiveFailures) {

  /** Abort on the first failed sample (default). */
  public static final FailurePolicy ABORT = new FailurePolicy(0);

  /** Log every failure and never abort. */
  public static final FailurePolicy IGNORE = new FailurePolicy(Integer.MAX_VALUE);

  /**
   * Continue while consecutive failures stay {@code <= n}; abort once the {@code (n+1)}-th
   * consecutive failure fires.
   *
   * @param n non-negative consecutive-failure budget
   * @return policy
   * @throws IllegalArgumentException if {@code n < 0}
   */
  public static FailurePolicy stopAfter(final int n) {
    if (n < 0) {
      throw new IllegalArgumentException("n must be >= 0, got: " + n);
    }
    return new FailurePolicy(n);
  }

  public FailurePolicy {
    if (maxConsecutiveFailures < 0) {
      throw new IllegalArgumentException(
          "maxConsecutiveFailures must be >= 0, got: " + maxConsecutiveFailures);
    }
  }
}
