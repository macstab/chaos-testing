/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.model;

/**
 * Represents the result of a replication consistency verification.
 *
 * <p>Captures total keys, matching keys, and missing keys, with computed consistency ratio and
 * assertion helpers.
 *
 * @param totalKeys total keys checked
 * @param matchingKeys keys that matched between master and replica
 * @param missingKeys keys missing on replica
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public record ConsistencyResult(int totalKeys, int matchingKeys, int missingKeys) {

  /**
   * Calculates consistency ratio (matching / total).
   *
   * @return consistency ratio between 0.0 and 1.0, or 0.0 if totalKeys is 0
   */
  public double consistencyRatio() {
    if (totalKeys == 0) {
      return 0.0;
    }
    return (double) matchingKeys / totalKeys;
  }

  /**
   * Checks if replication is fully consistent (all keys matched).
   *
   * @return true if consistency ratio is 1.0
   */
  public boolean isFullyConsistent() {
    return consistencyRatio() == 1.0;
  }

  /**
   * Asserts that replication is fully consistent.
   *
   * @throws AssertionError if not fully consistent
   */
  public void assertFullConsistency() {
    if (!isFullyConsistent()) {
      throw new AssertionError(
          String.format(
              "Expected full consistency but found %d/%d matching keys (%.2f%% consistent)",
              matchingKeys, totalKeys, consistencyRatio() * 100));
    }
  }

  /**
   * Asserts that consistency ratio meets a minimum threshold.
   *
   * @param minRatio minimum acceptable ratio (0.0 to 1.0)
   * @throws AssertionError if consistency ratio is below threshold
   */
  public void assertConsistencyAtLeast(final double minRatio) {
    final double actualRatio = consistencyRatio();
    if (actualRatio < minRatio) {
      throw new AssertionError(
          String.format(
              "Expected consistency ratio at least %.2f%% but was %.2f%% (%d/%d matching keys)",
              minRatio * 100, actualRatio * 100, matchingKeys, totalKeys));
    }
  }
}
