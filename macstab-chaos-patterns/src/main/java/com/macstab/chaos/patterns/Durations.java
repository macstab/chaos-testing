/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.time.Duration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class Durations {

  private Durations() {}

  public static Duration millis(final long millis) {
    return Duration.ofMillis(millis);
  }

  public static Duration seconds(final long seconds) {
    return Duration.ofSeconds(seconds);
  }

  public static Duration second(final long seconds) {
    return Duration.ofSeconds(seconds);
  }

  public static Duration minutes(final long minutes) {
    return Duration.ofMinutes(minutes);
  }

  public static Duration minute(final long minutes) {
    return Duration.ofMinutes(minutes);
  }

  public static Duration hours(final long hours) {
    return Duration.ofHours(hours);
  }

  public static Duration hour(final long hours) {
    return Duration.ofHours(hours);
  }

  /**
   * Validates a duration is non-null and strictly positive.
   *
   * @param value duration to check
   * @param fieldName name used in the error message
   * @throws NullPointerException if {@code value} is null
   * @throws IllegalArgumentException if {@code value} is zero or negative
   */
  public static Duration requirePositive(final Duration value, final String fieldName) {
    if (value == null) {
      throw new NullPointerException(fieldName + " must not be null");
    }
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(fieldName + " must be positive, got: " + value);
    }
    return value;
  }
}
