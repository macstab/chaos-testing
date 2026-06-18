/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.assertion;

/**
 * Fluent assertion API for read/write ratios.
 *
 * <p>Supports chaining back to {@link RedisCommandAssert} for additional assertions.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class RatioAssert {

  private final RedisCommandAssert parent;
  private final double actualRatio;

  /**
   * Package-private constructor for internal use.
   *
   * @param parent parent assertion
   * @param actualRatio actual ratio value
   */
  RatioAssert(final RedisCommandAssert parent, final double actualRatio) {
    this.parent = parent;
    this.actualRatio = actualRatio;
  }

  /**
   * Asserts that ratio is greater than the expected value.
   *
   * @param expected expected minimum (exclusive)
   * @return parent assertion for chaining
   * @throws AssertionError if ratio is not greater
   */
  public RedisCommandAssert greaterThan(final double expected) {
    if (actualRatio <= expected) {
      throw new AssertionError(
          String.format("Expected read/write ratio > %.2f but was %.2f", expected, actualRatio));
    }
    return parent;
  }

  /**
   * Asserts that ratio is less than the expected value.
   *
   * @param expected expected maximum (exclusive)
   * @return parent assertion for chaining
   * @throws AssertionError if ratio is not less
   */
  public RedisCommandAssert lessThan(final double expected) {
    if (actualRatio >= expected) {
      throw new AssertionError(
          String.format("Expected read/write ratio < %.2f but was %.2f", expected, actualRatio));
    }
    return parent;
  }

  /**
   * Asserts that ratio is between min and max (inclusive).
   *
   * @param min minimum expected ratio (inclusive)
   * @param max maximum expected ratio (inclusive)
   * @return parent assertion for chaining
   * @throws AssertionError if ratio is outside range
   */
  public RedisCommandAssert between(final double min, final double max) {
    if (actualRatio < min || actualRatio > max) {
      throw new AssertionError(
          String.format(
              "Expected read/write ratio between %.2f and %.2f but was %.2f",
              min, max, actualRatio));
    }
    return parent;
  }
}
