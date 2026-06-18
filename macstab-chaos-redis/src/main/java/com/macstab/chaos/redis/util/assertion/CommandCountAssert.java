/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.assertion;

/**
 * Fluent assertion API for command counts.
 *
 * <p>Supports chaining back to {@link RedisCommandAssert} for additional assertions.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class CommandCountAssert {

  private final RedisCommandAssert parent;
  private final String command;
  private final long actualCount;

  /**
   * Package-private constructor for internal use.
   *
   * @param parent parent assertion
   * @param command command name
   * @param actualCount actual count
   */
  CommandCountAssert(
      final RedisCommandAssert parent, final String command, final long actualCount) {
    this.parent = parent;
    this.command = command;
    this.actualCount = actualCount;
  }

  /**
   * Asserts that command count is at least the specified minimum.
   *
   * @param minCount minimum expected count
   * @return parent assertion for chaining
   * @throws AssertionError if actual count is below minimum
   */
  public RedisCommandAssert atLeast(final long minCount) {
    if (actualCount < minCount) {
      throw new AssertionError(
          String.format(
              "Expected at least %d %s commands but found %d", minCount, command, actualCount));
    }
    return parent;
  }

  /**
   * Asserts that command count is at most the specified maximum.
   *
   * @param maxCount maximum expected count
   * @return parent assertion for chaining
   * @throws AssertionError if actual count exceeds maximum
   */
  public RedisCommandAssert atMost(final long maxCount) {
    if (actualCount > maxCount) {
      throw new AssertionError(
          String.format(
              "Expected at most %d %s commands but found %d", maxCount, command, actualCount));
    }
    return parent;
  }

  /**
   * Asserts that command count exactly matches the expected value.
   *
   * @param expectedCount expected count
   * @return parent assertion for chaining
   * @throws AssertionError if actual count does not match
   */
  public RedisCommandAssert exactly(final long expectedCount) {
    if (actualCount != expectedCount) {
      throw new AssertionError(
          String.format(
              "Expected exactly %d %s commands but found %d", expectedCount, command, actualCount));
    }
    return parent;
  }

  /**
   * Asserts that command was never executed (count = 0).
   *
   * @return parent assertion for chaining
   * @throws AssertionError if command was executed
   */
  public RedisCommandAssert never() {
    return exactly(0);
  }
}
