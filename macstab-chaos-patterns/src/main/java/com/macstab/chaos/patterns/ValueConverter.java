/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

/**
 * Converts Double pattern values to chaos-specific types.
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>CPU: Double → Integer (50.5 → 50%)
 *   <li>Memory: Double → String (512.0 → "512M")
 *   <li>Disk: Double → Integer (75.2 → 75%)
 * </ul>
 *
 * @param <T> target type
 * @author Christian Schnapka - Macstab GmbH
 */
@FunctionalInterface
public interface ValueConverter<T> {

  /**
   * Convert pattern value to chaos-specific type.
   *
   * @param value pattern value (Double)
   * @return converted value
   */
  T convert(Double value);

  /** Integer converter (rounds). */
  static ValueConverter<Integer> toInteger() {
    return value -> (int) Math.round(value);
  }

  /** String converter (MB suffix). */
  static ValueConverter<String> toMemoryMB() {
    return value -> ((int) Math.round(value)) + "M";
  }

  /** String converter (GB suffix). */
  static ValueConverter<String> toMemoryGB() {
    return value -> ((int) Math.round(value)) + "G";
  }

  /** No-op converter (Double → Double). */
  static ValueConverter<Double> identity() {
    return value -> value;
  }
}
