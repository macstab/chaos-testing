/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Matches Redis commands by key glob patterns from captured MONITOR lines.
 *
 * <p><strong>Purpose:</strong> Enables key affinity testing by counting or listing commands that
 * operated on keys matching a given glob pattern.
 *
 * <p><strong>Glob Syntax:</strong>
 *
 * <ul>
 *   <li>{@code *} — matches any sequence of characters
 *   <li>{@code ?} — matches any single character
 *   <li>{@code .} — treated as a literal dot
 * </ul>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * KeyPatternMatcher matcher = new KeyPatternMatcher(capturedCommands);
 * long count = matcher.countCommandsMatchingKeyPattern("GET", "user:*");
 * List<String> lines = matcher.getCommandsMatchingKeyPattern("session:*");
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class KeyPatternMatcher {

  private final List<String> capturedCommands;

  /**
   * Creates a matcher over the given captured command lines.
   *
   * @param capturedCommands captured MONITOR output lines (must not be null)
   */
  public KeyPatternMatcher(final List<String> capturedCommands) {
    this.capturedCommands = Objects.requireNonNull(capturedCommands, "capturedCommands");
  }

  /**
   * Counts commands matching both the command name and key pattern.
   *
   * @param command Redis command name (e.g., "GET", "SET") — must not be null
   * @param keyPattern glob key pattern (e.g., "user:*") — must not be null
   * @return number of matching commands
   */
  public long countCommandsMatchingKeyPattern(final String command, final String keyPattern) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(keyPattern, "keyPattern");

    final String quotedCommand = "\"" + command.toUpperCase() + "\"";
    final Pattern pattern = convertGlobToRegex(keyPattern);

    return capturedCommands.stream()
        .filter(line -> line.contains(quotedCommand))
        .filter(line -> matchesKeyPattern(line, pattern))
        .count();
  }

  /**
   * Returns all command lines whose key matches the given glob pattern.
   *
   * @param keyPattern glob key pattern (e.g., "cache:*") — must not be null
   * @return list of matching MONITOR lines (never null)
   */
  public List<String> getCommandsMatchingKeyPattern(final String keyPattern) {
    Objects.requireNonNull(keyPattern, "keyPattern");
    final Pattern pattern = convertGlobToRegex(keyPattern);
    return capturedCommands.stream()
        .filter(line -> matchesKeyPattern(line, pattern))
        .collect(Collectors.toList());
  }

  // ==================== Private helpers ====================

  private boolean matchesKeyPattern(final String line, final Pattern pattern) {
    final String key = extractFirstKey(line);
    return key != null && pattern.matcher(key).matches();
  }

  /**
   * Converts a glob pattern to a compiled regex pattern.
   *
   * @param glob glob pattern
   * @return compiled regex pattern
   */
  static Pattern convertGlobToRegex(final String glob) {
    final String regex =
        glob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
    return Pattern.compile("^" + regex + "$");
  }

  /**
   * Extracts the first key argument from a MONITOR output line.
   *
   * <p>Example: {@code [0 172.17.0.1:54321] "GET" "user:123"} → {@code user:123}
   *
   * @param line MONITOR output line
   * @return extracted key, or {@code null} if not found
   */
  static String extractFirstKey(final String line) {
    final int cmdEnd = line.indexOf('"', line.indexOf('"') + 1);
    if (cmdEnd == -1) {
      return null;
    }
    final int keyStart = line.indexOf('"', cmdEnd + 1);
    if (keyStart == -1) {
      return null;
    }
    final int keyEnd = line.indexOf('"', keyStart + 1);
    if (keyEnd == -1) {
      return null;
    }
    return line.substring(keyStart + 1, keyEnd);
  }
}
