/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Detects "hot keys" (frequently accessed keys) from captured MONITOR output.
 *
 * <p>Hot keys can cause performance bottlenecks in Redis clusters due to uneven load distribution.
 * Use this detector to identify keys that should be cached, sharded, or rate-limited.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * HotKeyDetector detector = new HotKeyDetector(capturedCommands);
 * List<KeyAccessCount> hotKeys = detector.getHotKeys(10);
 * assertThat(hotKeys.get(0).key()).isEqualTo("user:1234");
 * assertThat(hotKeys.get(0).count()).isGreaterThan(100);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class HotKeyDetector {

  /**
   * Represents a key with its access count.
   *
   * @param key Redis key name
   * @param count number of times accessed
   */
  public record KeyAccessCount(String key, long count) implements Comparable<KeyAccessCount> {
    @Override
    public int compareTo(final KeyAccessCount o) {
      return Long.compare(o.count, this.count); // descending order
    }
  }

  private final List<String> capturedCommands;

  /**
   * Creates a hot key detector over captured MONITOR output.
   *
   * @param capturedCommands captured MONITOR lines — must not be null
   * @throws NullPointerException if capturedCommands is null
   */
  public HotKeyDetector(final List<String> capturedCommands) {
    this.capturedCommands = Objects.requireNonNull(capturedCommands, "capturedCommands");
  }

  /**
   * Returns top N hottest keys by access count (descending).
   *
   * @param topN number of keys to return
   * @return list of key access counts (sorted descending)
   */
  public List<KeyAccessCount> getHotKeys(final int topN) {
    final Map<String, Long> freqMap = buildFrequencyMap();
    return freqMap.entrySet().stream()
        .map(e -> new KeyAccessCount(e.getKey(), e.getValue()))
        .sorted()
        .limit(topN)
        .collect(Collectors.toList());
  }

  /**
   * Returns keys exceeding a threshold access count.
   *
   * @param threshold minimum access count
   * @return list of key access counts (sorted descending)
   */
  public List<KeyAccessCount> getKeysExceeding(final long threshold) {
    final Map<String, Long> freqMap = buildFrequencyMap();
    return freqMap.entrySet().stream()
        .filter(e -> e.getValue() >= threshold)
        .map(e -> new KeyAccessCount(e.getKey(), e.getValue()))
        .sorted()
        .collect(Collectors.toList());
  }

  /**
   * Returns access count for a specific key.
   *
   * @param key Redis key name — must not be null
   * @return access count, or 0 if key not found
   * @throws NullPointerException if key is null
   */
  public long getAccessCount(final String key) {
    Objects.requireNonNull(key, "key");
    final Map<String, Long> freqMap = buildFrequencyMap();
    return freqMap.getOrDefault(key, 0L);
  }

  /**
   * Returns total number of distinct keys accessed.
   *
   * @return distinct key count
   */
  public int getDistinctKeyCount() {
    return buildFrequencyMap().size();
  }

  /**
   * Builds frequency map of key accesses.
   *
   * @return map of key to access count (never null)
   */
  private Map<String, Long> buildFrequencyMap() {
    final Map<String, Long> freqMap = new HashMap<>();
    for (final String line : capturedCommands) {
      final String key = extractKey(line);
      if (key != null) {
        freqMap.merge(key, 1L, Long::sum);
      }
    }
    return Collections.unmodifiableMap(freqMap);
  }

  /**
   * Extracts the first key from a MONITOR line.
   *
   * <p>Uses the same logic as {@link KeyPatternMatcher#extractFirstKey(String)}.
   *
   * @param line MONITOR output line
   * @return extracted key, or null if no key found
   */
  private static String extractKey(final String line) {
    return KeyPatternMatcher.extractFirstKey(line);
  }
}
