/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Detects "big keys" (keys with large values) from captured MONITOR output.
 *
 * <p>Big keys can cause memory pressure, slow replication, and increased latency. This detector
 * identifies keys with large values by analyzing the MONITOR representation.
 *
 * <p><strong>Important:</strong> Size measurements are <em>approximate</em>. The detector measures
 * string length of MONITOR representation, not serialized byte size. This is sufficient for
 * detecting obviously large values in test scenarios, but should not be used for production
 * capacity planning.
 *
 * <p><strong>Supported Commands:</strong> SET, HSET, LPUSH, RPUSH, SADD, ZADD
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * BigKeyDetector detector = new BigKeyDetector(capturedCommands);
 * List<KeySizeEntry> bigKeys = detector.getBigKeys(10);
 * assertThat(bigKeys.get(0).approximateSizeBytes()).isGreaterThan(10000);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class BigKeyDetector {

  /**
   * Represents a key with its approximate size.
   *
   * <p>Sizes are approximate — based on string length of MONITOR representation, not actual
   * serialized byte size.
   *
   * @param key Redis key name
   * @param command Redis command that wrote this value
   * @param approximateSizeBytes approximate size in bytes (character count)
   */
  public record KeySizeEntry(String key, String command, int approximateSizeBytes)
      implements Comparable<KeySizeEntry> {
    @Override
    public int compareTo(final KeySizeEntry o) {
      return Integer.compare(o.approximateSizeBytes, this.approximateSizeBytes); // descending order
    }
  }

  private final List<String> capturedCommands;

  /**
   * Creates a big key detector over captured MONITOR output.
   *
   * @param capturedCommands captured MONITOR lines — must not be null
   * @throws NullPointerException if capturedCommands is null
   */
  public BigKeyDetector(final List<String> capturedCommands) {
    this.capturedCommands = Objects.requireNonNull(capturedCommands, "capturedCommands");
  }

  /**
   * Returns top N biggest keys by approximate size (descending).
   *
   * @param topN number of keys to return
   * @return list of key size entries (sorted descending)
   */
  public List<KeySizeEntry> getBigKeys(final int topN) {
    return buildKeySizeList().stream().sorted().limit(topN).collect(Collectors.toList());
  }

  /**
   * Returns keys exceeding a size threshold.
   *
   * @param thresholdBytes minimum size in bytes
   * @return list of key size entries (sorted descending)
   */
  public List<KeySizeEntry> getKeysExceedingSize(final int thresholdBytes) {
    return buildKeySizeList().stream()
        .filter(e -> e.approximateSizeBytes >= thresholdBytes)
        .sorted()
        .collect(Collectors.toList());
  }

  /**
   * Returns the largest key found.
   *
   * @return largest key, or empty if no writeable commands captured
   */
  public Optional<KeySizeEntry> getLargestKey() {
    return buildKeySizeList().stream()
        .max(Comparator.comparingInt(KeySizeEntry::approximateSizeBytes));
  }

  /**
   * Builds list of all keys with their sizes.
   *
   * @return list of key size entries (never null, may be empty)
   */
  private List<KeySizeEntry> buildKeySizeList() {
    final List<KeySizeEntry> entries = new ArrayList<>();
    for (final String line : capturedCommands) {
      final KeySizeEntry entry = parseKeySize(line);
      if (entry != null) {
        entries.add(entry);
      }
    }
    return Collections.unmodifiableList(entries);
  }

  /**
   * Parses a MONITOR line for key and value size.
   *
   * <p>Supports: SET, HSET, LPUSH, RPUSH, SADD, ZADD
   *
   * @param line MONITOR output line
   * @return key size entry, or null if not a writeable command or no value extractable
   */
  private static KeySizeEntry parseKeySize(final String line) {
    final String command = extractCommandName(line);
    if (command == null) {
      return null;
    }

    final String key;
    final String value;

    // SET: "SET" "key" "value"
    if ("SET".equals(command)) {
      key = extractToken(line, 1);
      value = extractToken(line, 2);
    }
    // HSET: "HSET" "key" "field" "value"
    else if ("HSET".equals(command)) {
      key = extractToken(line, 1);
      value = extractToken(line, 3);
    }
    // LPUSH/RPUSH: "LPUSH" "key" "value"
    else if ("LPUSH".equals(command) || "RPUSH".equals(command)) {
      key = extractToken(line, 1);
      value = extractToken(line, 2);
    }
    // SADD: "SADD" "key" "value"
    else if ("SADD".equals(command)) {
      key = extractToken(line, 1);
      value = extractToken(line, 2);
    }
    // ZADD: "ZADD" "key" "score" "member"
    else if ("ZADD".equals(command)) {
      key = extractToken(line, 1);
      value = extractToken(line, 3);
    } else {
      return null; // Not a writeable command we track
    }

    if (key == null || value == null) {
      return null; // Malformed line or missing data
    }

    return new KeySizeEntry(key, command, value.length());
  }

  /**
   * Extracts command name from a MONITOR line.
   *
   * @param line MONITOR output line
   * @return command name (uppercase), or null if not found
   * @see CommandParser#extractCommandName(String)
   */
  private static String extractCommandName(final String line) {
    return CommandParser.extractCommandName(line);
  }

  /**
   * Extracts the Nth quoted token from a MONITOR line (0-indexed).
   *
   * <p>Each token is a quoted pair. Index 0 = command name, 1 = first argument, etc. The algorithm
   * skips N full quote-pairs (open + close) before extracting the target token.
   *
   * @param line MONITOR output line
   * @param tokenIndex token index (0=command, 1=first arg, 2=second arg, etc.)
   * @return extracted token, or null if not found
   */
  private static String extractToken(final String line, final int tokenIndex) {
    int pos = 0;
    // Skip tokenIndex full quote-pairs (open + close) to reach the target token
    for (int i = 0; i < tokenIndex; i++) {
      final int openQuote = line.indexOf('"', pos);
      if (openQuote == -1) {
        return null;
      }
      final int closeQuote = line.indexOf('"', openQuote + 1);
      if (closeQuote == -1) {
        return null;
      }
      pos = closeQuote + 1;
    }
    // Extract target token
    final int openQuote = line.indexOf('"', pos);
    if (openQuote == -1) {
      return null;
    }
    final int closeQuote = line.indexOf('"', openQuote + 1);
    if (closeQuote == -1) {
      return null;
    }
    return line.substring(openQuote + 1, closeQuote);
  }
}
