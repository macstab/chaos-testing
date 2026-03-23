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
}
