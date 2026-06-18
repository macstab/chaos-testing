/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.model;

import java.time.Instant;

/**
 * Represents a snapshot of Redis memory usage at a specific point in time.
 *
 * <p>Captures used memory, peak memory, fragmentation ratio, and snapshot timestamp. Supports delta
 * calculations for memory leak detection.
 *
 * @param usedMemoryBytes current used memory in bytes
 * @param usedMemoryPeakBytes peak used memory in bytes
 * @param fragmentationRatio memory fragmentation ratio
 * @param capturedAt timestamp when snapshot was captured
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public record MemorySnapshot(
    long usedMemoryBytes, long usedMemoryPeakBytes, double fragmentationRatio, Instant capturedAt) {

  /**
   * Calculates memory delta from an earlier snapshot.
   *
   * @param earlier earlier snapshot — must not be null
   * @return memory delta in bytes (positive = growth, negative = reduction)
   */
  public long deltaFrom(final MemorySnapshot earlier) {
    return this.usedMemoryBytes - earlier.usedMemoryBytes;
  }
}
