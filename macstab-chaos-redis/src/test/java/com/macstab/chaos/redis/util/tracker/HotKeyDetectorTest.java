/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.util.tracker.HotKeyDetector.KeyAccessCount;

/** Comprehensive unit tests for {@link HotKeyDetector}. */
@DisplayName("HotKeyDetector")
class HotKeyDetectorTest {

  private static final String GET_USER1 = "1234.567 [0 127.0.0.1:12345] \"GET\" \"user:123\"";
  private static final String GET_USER1_AGAIN = "1234.568 [0 127.0.0.1:12345] \"GET\" \"user:123\"";
  private static final String GET_USER2 = "1234.569 [0 127.0.0.1:12345] \"GET\" \"user:456\"";
  private static final String SET_SESSION = "1234.570 [0 127.0.0.1:12345] \"SET\" \"session:abc\"";
  private static final String GET_SESSION = "1234.571 [0 127.0.0.1:12345] \"GET\" \"session:abc\"";
  private static final String GET_PRODUCT = "1234.572 [0 127.0.0.1:12345] \"GET\" \"product:789\"";

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null capturedCommands")
    void shouldThrowForNull() {
      assertThatThrownBy(() -> new HotKeyDetector(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("capturedCommands");
    }

    @Test
    @DisplayName("Should accept empty list")
    void shouldAcceptEmptyList() {
      final HotKeyDetector detector = new HotKeyDetector(List.of());
      assertThat(detector.getHotKeys(10)).isEmpty();
    }
  }

  @Nested
  @DisplayName("getHotKeys()")
  class GetHotKeys {

    @Test
    @DisplayName("Should return top N keys sorted by access count descending")
    void shouldReturnTopNKeys() {
      // ARRANGE: user:123 accessed 2 times, user:456 once, session:abc once
      final HotKeyDetector detector =
          new HotKeyDetector(List.of(GET_USER1, GET_USER1_AGAIN, GET_USER2, GET_SESSION));

      // ACT
      final List<KeyAccessCount> hotKeys = detector.getHotKeys(3);

      // ASSERT
      assertThat(hotKeys).hasSize(3);
      assertThat(hotKeys.get(0).key()).isEqualTo("user:123");
      assertThat(hotKeys.get(0).count()).isEqualTo(2);
      assertThat(hotKeys.get(1).key()).isIn("user:456", "session:abc");
      assertThat(hotKeys.get(1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should limit results to topN")
    void shouldLimitResultsToTopN() {
      final HotKeyDetector detector =
          new HotKeyDetector(List.of(GET_USER1, GET_USER2, GET_SESSION, GET_PRODUCT));

      final List<KeyAccessCount> hotKeys = detector.getHotKeys(2);

      assertThat(hotKeys).hasSize(2);
    }

    @Test
    @DisplayName("Should handle topN larger than distinct key count")
    void shouldHandleTopNLargerThanKeyCount() {
      final HotKeyDetector detector = new HotKeyDetector(List.of(GET_USER1, GET_USER2));

      final List<KeyAccessCount> hotKeys = detector.getHotKeys(10);

      assertThat(hotKeys).hasSize(2);
    }

    @Test
    @DisplayName("Should return empty list for zero topN")
    void shouldReturnEmptyForZeroTopN() {
      final HotKeyDetector detector = new HotKeyDetector(List.of(GET_USER1));

      final List<KeyAccessCount> hotKeys = detector.getHotKeys(0);

      assertThat(hotKeys).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list for empty input")
    void shouldReturnEmptyForEmptyInput() {
      final HotKeyDetector detector = new HotKeyDetector(List.of());

      final List<KeyAccessCount> hotKeys = detector.getHotKeys(10);

      assertThat(hotKeys).isEmpty();
    }

    @Test
    @DisplayName("Should track multiple keys correctly")
    void shouldTrackMultipleKeysCorrectly() {
      // ARRANGE: Create a hot key pattern
      final List<String> commands =
          List.of(
              "1234.0 [0 127.0.0.1:12345] \"GET\" \"hot:key\"",
              "1234.1 [0 127.0.0.1:12345] \"GET\" \"hot:key\"",
              "1234.2 [0 127.0.0.1:12345] \"GET\" \"hot:key\"",
              "1234.3 [0 127.0.0.1:12345] \"GET\" \"hot:key\"",
              "1234.4 [0 127.0.0.1:12345] \"GET\" \"hot:key\"",
              "1234.5 [0 127.0.0.1:12345] \"GET\" \"warm:key\"",
              "1234.6 [0 127.0.0.1:12345] \"GET\" \"warm:key\"",
              "1234.7 [0 127.0.0.1:12345] \"GET\" \"cold:key\"");

      final HotKeyDetector detector = new HotKeyDetector(commands);

      // ACT
      final List<KeyAccessCount> hotKeys = detector.getHotKeys(3);

      // ASSERT
      assertThat(hotKeys).hasSize(3);
      assertThat(hotKeys.get(0).key()).isEqualTo("hot:key");
      assertThat(hotKeys.get(0).count()).isEqualTo(5);
      assertThat(hotKeys.get(1).key()).isEqualTo("warm:key");
      assertThat(hotKeys.get(1).count()).isEqualTo(2);
      assertThat(hotKeys.get(2).key()).isEqualTo("cold:key");
      assertThat(hotKeys.get(2).count()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("getKeysExceeding()")
  class GetKeysExceeding {

    @Test
    @DisplayName("Should return keys exceeding threshold")
    void shouldReturnKeysExceedingThreshold() {
      // ARRANGE
      final List<String> commands =
          List.of(
              "1234.0 [0 127.0.0.1:12345] \"GET\" \"user:1\"",
              "1234.1 [0 127.0.0.1:12345] \"GET\" \"user:1\"",
              "1234.2 [0 127.0.0.1:12345] \"GET\" \"user:1\"",
              "1234.3 [0 127.0.0.1:12345] \"GET\" \"user:2\"",
              "1234.4 [0 127.0.0.1:12345] \"GET\" \"user:2\"",
              "1234.5 [0 127.0.0.1:12345] \"GET\" \"user:3\"");

      final HotKeyDetector detector = new HotKeyDetector(commands);

      // ACT
      final List<KeyAccessCount> result = detector.getKeysExceeding(2);

      // ASSERT
      assertThat(result).hasSize(2);
      assertThat(result.get(0).key()).isEqualTo("user:1");
      assertThat(result.get(0).count()).isEqualTo(3);
      assertThat(result.get(1).key()).isEqualTo("user:2");
      assertThat(result.get(1).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should include keys exactly at threshold")
    void shouldIncludeKeysAtThreshold() {
      final HotKeyDetector detector =
          new HotKeyDetector(List.of(GET_USER1, GET_USER1_AGAIN, GET_USER2));

      final List<KeyAccessCount> result = detector.getKeysExceeding(2);

      assertThat(result).hasSize(1);
      assertThat(result.get(0).key()).isEqualTo("user:123");
      assertThat(result.get(0).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should return empty list if no keys exceed threshold")
    void shouldReturnEmptyIfNoKeysExceed() {
      final HotKeyDetector detector = new HotKeyDetector(List.of(GET_USER1, GET_USER2));

      final List<KeyAccessCount> result = detector.getKeysExceeding(10);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should handle threshold of 0")
    void shouldHandleThresholdZero() {
      final HotKeyDetector detector = new HotKeyDetector(List.of(GET_USER1, GET_USER2));

      final List<KeyAccessCount> result = detector.getKeysExceeding(0);

      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("Should return sorted by count descending")
    void shouldReturnSortedDescending() {
      final List<String> commands =
          List.of(
              "1234.0 [0 127.0.0.1:12345] \"GET\" \"a\"",
              "1234.1 [0 127.0.0.1:12345] \"GET\" \"b\"",
              "1234.2 [0 127.0.0.1:12345] \"GET\" \"b\"",
              "1234.3 [0 127.0.0.1:12345] \"GET\" \"c\"",
              "1234.4 [0 127.0.0.1:12345] \"GET\" \"c\"",
              "1234.5 [0 127.0.0.1:12345] \"GET\" \"c\"");

      final HotKeyDetector detector = new HotKeyDetector(commands);
      final List<KeyAccessCount> result = detector.getKeysExceeding(1);

      assertThat(result).hasSize(3);
      assertThat(result.get(0).count()).isGreaterThanOrEqualTo(result.get(1).count());
      assertThat(result.get(1).count()).isGreaterThanOrEqualTo(result.get(2).count());
    }
  }

  @Nested
  @DisplayName("getAccessCount()")
  class GetAccessCount {

    @Test
    @DisplayName("Should return correct access count for existing key")
    void shouldReturnCorrectAccessCount() {
      final HotKeyDetector detector =
          new HotKeyDetector(List.of(GET_USER1, GET_USER1_AGAIN, GET_USER2));

      assertThat(detector.getAccessCount("user:123")).isEqualTo(2);
      assertThat(detector.getAccessCount("user:456")).isEqualTo(1);
    }

    @Test
    @DisplayName("Should return 0 for non-existent key")
    void shouldReturnZeroForNonExistent() {
      final HotKeyDetector detector = new HotKeyDetector(List.of(GET_USER1));

      assertThat(detector.getAccessCount("nonexistent")).isEqualTo(0);
    }

    @Test
    @DisplayName("Should throw NPE for null key")
    void shouldThrowForNullKey() {
      final HotKeyDetector detector = new HotKeyDetector(List.of(GET_USER1));

      assertThatThrownBy(() -> detector.getAccessCount(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("key");
    }

    @Test
    @DisplayName("Should handle empty commands list")
    void shouldHandleEmptyCommandsList() {
      final HotKeyDetector detector = new HotKeyDetector(List.of());

      assertThat(detector.getAccessCount("anykey")).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("getDistinctKeyCount()")
  class GetDistinctKeyCount {

    @Test
    @DisplayName("Should return correct distinct key count")
    void shouldReturnCorrectDistinctCount() {
      final HotKeyDetector detector =
          new HotKeyDetector(List.of(GET_USER1, GET_USER1_AGAIN, GET_USER2, GET_SESSION));

      assertThat(detector.getDistinctKeyCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should return 0 for empty list")
    void shouldReturnZeroForEmpty() {
      final HotKeyDetector detector = new HotKeyDetector(List.of());

      assertThat(detector.getDistinctKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should count only unique keys")
    void shouldCountOnlyUniqueKeys() {
      final List<String> commands =
          List.of(
              "1234.0 [0 127.0.0.1:12345] \"GET\" \"key:1\"",
              "1234.1 [0 127.0.0.1:12345] \"GET\" \"key:1\"",
              "1234.2 [0 127.0.0.1:12345] \"GET\" \"key:1\"",
              "1234.3 [0 127.0.0.1:12345] \"GET\" \"key:1\"");

      final HotKeyDetector detector = new HotKeyDetector(commands);

      assertThat(detector.getDistinctKeyCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("KeyAccessCount record")
  class KeyAccessCountRecord {

    @Test
    @DisplayName("Should implement Comparable correctly")
    void shouldImplementComparable() {
      final KeyAccessCount high = new KeyAccessCount("key1", 100);
      final KeyAccessCount low = new KeyAccessCount("key2", 10);

      assertThat(high.compareTo(low)).isLessThan(0); // descending order
      assertThat(low.compareTo(high)).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should handle equal counts")
    void shouldHandleEqualCounts() {
      final KeyAccessCount kac1 = new KeyAccessCount("key1", 50);
      final KeyAccessCount kac2 = new KeyAccessCount("key2", 50);

      assertThat(kac1.compareTo(kac2)).isEqualTo(0);
    }

    @Test
    @DisplayName("Should be sortable in collections")
    void shouldBeSortableInCollections() {
      final List<KeyAccessCount> list =
          List.of(
              new KeyAccessCount("key1", 10),
              new KeyAccessCount("key2", 50),
              new KeyAccessCount("key3", 30));

      final List<KeyAccessCount> sorted = list.stream().sorted().toList();

      assertThat(sorted.get(0).count()).isEqualTo(50);
      assertThat(sorted.get(1).count()).isEqualTo(30);
      assertThat(sorted.get(2).count()).isEqualTo(10);
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("Should ignore lines without extractable keys")
    void shouldIgnoreLinesWithoutKeys() {
      final List<String> commands =
          List.of(
              "1234.0 [0 127.0.0.1:12345] \"PING\"",
              "1234.1 [0 127.0.0.1:12345] \"GET\" \"user:1\"",
              "malformed line",
              "1234.2 [0 127.0.0.1:12345] \"GET\" \"user:1\"");

      final HotKeyDetector detector = new HotKeyDetector(commands);

      assertThat(detector.getDistinctKeyCount()).isEqualTo(1);
      assertThat(detector.getAccessCount("user:1")).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle different commands on same key")
    void shouldHandleDifferentCommandsOnSameKey() {
      final List<String> commands =
          List.of(
              "1234.0 [0 127.0.0.1:12345] \"GET\" \"mykey\"",
              "1234.1 [0 127.0.0.1:12345] \"SET\" \"mykey\" \"value\"",
              "1234.2 [0 127.0.0.1:12345] \"DEL\" \"mykey\"");

      final HotKeyDetector detector = new HotKeyDetector(commands);

      assertThat(detector.getAccessCount("mykey")).isEqualTo(3);
    }

    @Test
    @DisplayName("Should handle keys with special characters")
    void shouldHandleKeysWithSpecialCharacters() {
      final List<String> commands =
          List.of(
              "1234.0 [0 127.0.0.1:12345] \"GET\" \"user:123:profile\"",
              "1234.1 [0 127.0.0.1:12345] \"GET\" \"cache::data\"",
              "1234.2 [0 127.0.0.1:12345] \"GET\" \"key-with-dash\"");

      final HotKeyDetector detector = new HotKeyDetector(commands);

      assertThat(detector.getDistinctKeyCount()).isEqualTo(3);
    }
  }
}
