/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.model;

import java.util.Objects;

/**
 * Memory pressure stall information (PSI) from cgroups v2.
 *
 * <p>Immutable value object representing memory.pressure metrics.
 *
 * @param some10s percentage of time at least one task stalled (10s avg)
 * @param some60s percentage of time at least one task stalled (60s avg)
 * @param some300s percentage of time at least one task stalled (300s avg)
 * @param full10s percentage of time all tasks stalled (10s avg)
 * @param full60s percentage of time all tasks stalled (60s avg)
 * @param full300s percentage of time all tasks stalled (300s avg)
 * @author Christian Schnapka - Macstab GmbH
 */
public record MemoryPressureInfo(
    double some10s,
    double some60s,
    double some300s,
    double full10s,
    double full60s,
    double full300s) {

  /**
   * Compact constructor that validates all pressure values are non-null.
   *
   * @throws NullPointerException if any parameter is null
   */
  public MemoryPressureInfo {
    Objects.requireNonNull(some10s, "some10s must not be null");
    Objects.requireNonNull(some60s, "some60s must not be null");
    Objects.requireNonNull(some300s, "some300s must not be null");
    Objects.requireNonNull(full10s, "full10s must not be null");
    Objects.requireNonNull(full60s, "full60s must not be null");
    Objects.requireNonNull(full300s, "full300s must not be null");
  }
}
