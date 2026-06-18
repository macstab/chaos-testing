/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.assertion;

import java.util.Objects;
import java.util.Set;

import com.macstab.chaos.redis.util.RedisCommandTracker;

/**
 * Fluent assertion API for {@link RedisCommandTracker}.
 *
 * <p>Provides chainable assertions for verifying command patterns, counts, and ratios.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * tracker.assertThat()
 *     .hasCommand("GET").atLeast(5)
 *     .hasNoDangerousCommands()
 *     .hasReadWriteRatio().greaterThan(2.0);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class RedisCommandAssert {

  static final Set<String> DANGEROUS_COMMANDS =
      Set.of("KEYS", "FLUSHALL", "FLUSHDB", "DEBUG", "SHUTDOWN");

  private final RedisCommandTracker tracker;

  /**
   * Creates a command assertion for the given tracker.
   *
   * @param tracker Redis command tracker — must not be null
   * @throws NullPointerException if tracker is null
   */
  public RedisCommandAssert(final RedisCommandTracker tracker) {
    this.tracker = Objects.requireNonNull(tracker, "tracker");
  }

  /**
   * Asserts that a command does not appear in captured output.
   *
   * @param command Redis command name — must not be null
   * @return this instance for chaining
   * @throws AssertionError if command is present
   */
  public RedisCommandAssert hasNoCommand(final String command) {
    Objects.requireNonNull(command, "command");
    final long count = tracker.countCommand(command);
    if (count > 0) {
      throw new AssertionError(
          String.format("Expected no %s commands but found %d", command, count));
    }
    return this;
  }

  /**
   * Asserts that no dangerous commands appear in captured output.
   *
   * <p>Dangerous commands: KEYS, FLUSHALL, FLUSHDB, DEBUG, SHUTDOWN
   *
   * @return this instance for chaining
   * @throws AssertionError if any dangerous command is present
   */
  public RedisCommandAssert hasNoDangerousCommands() {
    final StringBuilder found = new StringBuilder();
    for (final String dangerousCmd : DANGEROUS_COMMANDS) {
      final long count = tracker.countCommand(dangerousCmd);
      if (count > 0) {
        if (!found.isEmpty()) {
          found.append(", ");
        }
        found.append(dangerousCmd).append(" (").append(count).append(")");
      }
    }

    if (!found.isEmpty()) {
      throw new AssertionError("Expected no dangerous commands but found: " + found);
    }
    return this;
  }

  /**
   * Begins a command count assertion.
   *
   * @param command Redis command name — must not be null
   * @return command count assertion builder
   */
  public CommandCountAssert hasCommand(final String command) {
    Objects.requireNonNull(command, "command");
    final long actualCount = tracker.countCommand(command);
    return new CommandCountAssert(this, command, actualCount);
  }

  /**
   * Begins a read/write ratio assertion.
   *
   * @return ratio assertion builder
   */
  public RatioAssert hasReadWriteRatio() {
    final double actualRatio = tracker.getReadWriteRatio();
    return new RatioAssert(this, actualRatio);
  }
}
