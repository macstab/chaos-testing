/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.time.Duration;
import java.util.Objects;

/**
 * Value with timestamp (when to apply).
 *
 * @param timestamp time offset from pattern start
 * @param value value to apply at this timestamp
 * @param <T> value type
 * @author Christian Schnapka - Macstab GmbH
 */
public record TimedValue<T>(Duration timestamp, T value) {

  public TimedValue {
    Objects.requireNonNull(timestamp, "timestamp must not be null");
    Objects.requireNonNull(value, "value must not be null");
  }
}
