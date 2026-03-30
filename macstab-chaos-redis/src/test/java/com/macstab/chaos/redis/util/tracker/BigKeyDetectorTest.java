/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.util.tracker.BigKeyDetector.KeySizeEntry;

/** Comprehensive unit tests for {@link BigKeyDetector}. */
@DisplayName("BigKeyDetector")
class BigKeyDetectorTest {

  private static final String SET_SMALL =
      "1234.567 [0 127.0.0.1:12345] \"SET\" \"key1\" \"small\"";
  private static final String SET_MEDIUM =
      "1234.568 [0 127.0.0.1:12345] \"SET\" \"key2\" \"medium-value\"";
  private static final String SET_LARGE =
      "1234.569 [0 127.0.0.1:12345] \"SET\" \"key3\" \"very-large-value-with-lots-of-data\"";
  private static final String HSET_VALUE =
      "1234.570 [0 127.0.0.1:12345] \"HSET\" \"hash:key\" \"field\" \"hashvalue\"";
  private static final String LPUSH_VALUE =
      "1234.571 [0 127.0.0.1:12345] \"LPUSH\" \"list:key\" \"listvalue\"";
  private static final String RPUSH_VALUE =
      "1234.572 [0 127.0.0.1:12345] \"RPUSH\" \"rlist:key\" \"rlistvalue\"";
  private static final String SADD_VALUE =
      "1234.573 [0 127.0.0.1:12345] \"SADD\" \"set:key\" \"setvalue\"";
  private static final String ZADD_VALUE =
      "1234.574 [0 127.0.0.1:12345] \"ZADD\" \"zset:key\" \"1.0\" \"member\"";
  private static final String GET_COMMAND = "1234.575 [0 127.0.0.1:12345] \"GET\" \"key1\"";
  private static final String PING_COMMAND = "1234.576 [0 127.0.0.1:12345] \"PING\"";

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null capturedCommands")
    void shouldThrowForNull() {
      assertThatThrownBy(() -> new BigKeyDetector(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("capturedCommands");
    }

    @Test
    @DisplayName("Should accept empty list")
    void shouldAcceptEmptyList() {
      final BigKeyDetector detector = new BigKeyDetector(List.of());
      assertThat(detector.getBigKeys(10)).isEmpty();
    }
  }

  @Nested
  @DisplayName("getBigKeys()")
  class GetBigKeys {

    @Test
    @DisplayName("Should return top N keys sorted by size descending")
    void shouldReturnTopNKeysSorted() {
      // ARRANGE: large=35, medium=12, small=5
      final BigKeyDetector detector = new BigKeyDetector(List.of(SET_SMALL, SET_MEDIUM, SET_LARGE));

      // ACT
      final List<KeySizeEntry> bigKeys = detector.getBigKeys(3);

      // ASSERT
      assertThat(bigKeys).hasSize(3);
      assertThat(bigKeys.get(0).key()).isEqualTo("key3");
      assertThat(bigKeys.get(0).approximateSizeBytes()).isEqualTo(34);
      assertThat(bigKeys.get(1).key()).isEqualTo("key2");
      assertThat(bigKeys.get(1).approximateSizeBytes()).isEqualTo(12);
      assertThat(bigKeys.get(2).key()).isEqualTo("key1");
      assertThat(bigKeys.get(2).approximateSizeBytes()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should limit results to topN")
    void shouldLimitResultsToTopN() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(SET_SMALL, SET_MEDIUM, SET_LARGE));

      final List<KeySizeEntry> bigKeys = detector.getBigKeys(2);

      assertThat(bigKeys).hasSize(2);
      assertThat(bigKeys.get(0).key()).isEqualTo("key3");
      assertThat(bigKeys.get(1).key()).isEqualTo("key2");
    }

    @Test
    @DisplayName("Should handle topN larger than distinct key count")
    void shouldHandleTopNLargerThanKeyCount() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(SET_SMALL, SET_MEDIUM));

      final List<KeySizeEntry> bigKeys = detector.getBigKeys(10);

      assertThat(bigKeys).hasSize(2);
    }

    @Test
    @DisplayName("Should return empty list for zero topN")
    void shouldReturnEmptyForZeroTopN() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(SET_LARGE));

      final List<KeySizeEntry> bigKeys = detector.getBigKeys(0);

      assertThat(bigKeys).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list for empty input")
    void shouldReturnEmptyForEmptyInput() {
      final BigKeyDetector detector = new BigKeyDetector(List.of());

      final List<KeySizeEntry> bigKeys = detector.getBigKeys(10);

      assertThat(bigKeys).isEmpty();
    }
  }

  @Nested
  @DisplayName("getKeysExceedingSize()")
  class GetKeysExceedingSize {

    @Test
    @DisplayName("Should return keys exceeding threshold")
    void shouldReturnKeysExceedingThreshold() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(SET_SMALL, SET_MEDIUM, SET_LARGE));

      final List<KeySizeEntry> result = detector.getKeysExceedingSize(10);

      assertThat(result).hasSize(2);
      assertThat(result.get(0).key()).isEqualTo("key3");
      assertThat(result.get(1).key()).isEqualTo("key2");
    }

    @Test
    @DisplayName("Should include keys exactly at threshold")
    void shouldIncludeKeysAtThreshold() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(SET_MEDIUM, SET_LARGE));

      final List<KeySizeEntry> result = detector.getKeysExceedingSize(12);

      assertThat(result).hasSize(2);
      assertThat(result.get(0).approximateSizeBytes()).isEqualTo(34);
      assertThat(result.get(1).approximateSizeBytes()).isEqualTo(12);
    }

    @Test
    @DisplayName("Should return empty list if no keys exceed threshold")
    void shouldReturnEmptyIfNoKeysExceed() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(SET_SMALL, SET_MEDIUM));

      final List<KeySizeEntry> result = detector.getKeysExceedingSize(100);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return all keys when threshold is zero")
    void shouldReturnAllKeysWhenThresholdZero() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(SET_SMALL, SET_MEDIUM, SET_LARGE));

      final List<KeySizeEntry> result = detector.getKeysExceedingSize(0);

      assertThat(result).hasSize(3);
    }
  }

  @Nested
  @DisplayName("getLargestKey()")
  class GetLargestKey {

    @Test
    @DisplayName("Should return largest key")
    void shouldReturnLargestKey() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(SET_SMALL, SET_MEDIUM, SET_LARGE));

      final var largest = detector.getLargestKey();

      assertThat(largest).isPresent();
      assertThat(largest.get().key()).isEqualTo("key3");
      assertThat(largest.get().approximateSizeBytes()).isEqualTo(34);
    }

    @Test
    @DisplayName("Should return empty when no writeable commands")
    void shouldReturnEmptyWhenNoWriteableCommands() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(GET_COMMAND, PING_COMMAND));

      final var largest = detector.getLargestKey();

      assertThat(largest).isEmpty();
    }

    @Test
    @DisplayName("Should return empty for empty list")
    void shouldReturnEmptyForEmptyList() {
      final BigKeyDetector detector = new BigKeyDetector(List.of());

      final var largest = detector.getLargestKey();

      assertThat(largest).isEmpty();
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("Should extract HSET value correctly")
    void shouldExtractHsetValue() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(HSET_VALUE));

      final var largest = detector.getLargestKey();

      assertThat(largest).isPresent();
      assertThat(largest.get().key()).isEqualTo("hash:key");
      assertThat(largest.get().command()).isEqualTo("HSET");
      assertThat(largest.get().approximateSizeBytes()).isEqualTo(9); // "hashvalue"
    }

    @Test
    @DisplayName("Should extract LPUSH value correctly")
    void shouldExtractLpushValue() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(LPUSH_VALUE));

      final var largest = detector.getLargestKey();

      assertThat(largest).isPresent();
      assertThat(largest.get().key()).isEqualTo("list:key");
      assertThat(largest.get().command()).isEqualTo("LPUSH");
      assertThat(largest.get().approximateSizeBytes()).isEqualTo(9); // "listvalue"
    }

    @Test
    @DisplayName("Should extract RPUSH value correctly")
    void shouldExtractRpushValue() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(RPUSH_VALUE));

      final var largest = detector.getLargestKey();

      assertThat(largest).isPresent();
      assertThat(largest.get().command()).isEqualTo("RPUSH");
    }

    @Test
    @DisplayName("Should extract SADD value correctly")
    void shouldExtractSaddValue() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(SADD_VALUE));

      final var largest = detector.getLargestKey();

      assertThat(largest).isPresent();
      assertThat(largest.get().command()).isEqualTo("SADD");
    }

    @Test
    @DisplayName("Should extract ZADD value correctly")
    void shouldExtractZaddValue() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(ZADD_VALUE));

      final var largest = detector.getLargestKey();

      assertThat(largest).isPresent();
      assertThat(largest.get().key()).isEqualTo("zset:key");
      assertThat(largest.get().command()).isEqualTo("ZADD");
      assertThat(largest.get().approximateSizeBytes()).isEqualTo(6); // "member"
    }

    @Test
    @DisplayName("Should skip lines without values")
    void shouldSkipLinesWithoutValues() {
      final BigKeyDetector detector = new BigKeyDetector(List.of(GET_COMMAND, PING_COMMAND, SET_SMALL));

      final List<KeySizeEntry> bigKeys = detector.getBigKeys(10);

      assertThat(bigKeys).hasSize(1);
      assertThat(bigKeys.get(0).key()).isEqualTo("key1");
    }

    @Test
    @DisplayName("Should ignore malformed lines")
    void shouldIgnoreMalformedLines() {
      final List<String> commands =
          List.of(
              "malformed line",
              SET_SMALL,
              "another malformed",
              SET_MEDIUM);

      final BigKeyDetector detector = new BigKeyDetector(commands);

      final List<KeySizeEntry> bigKeys = detector.getBigKeys(10);

      assertThat(bigKeys).hasSize(2);
    }

    @Test
    @DisplayName("Should handle incomplete SET command")
    void shouldHandleIncompleteSetCommand() {
      final String incomplete = "1234.567 [0 127.0.0.1:12345] \"SET\" \"key\"";
      final BigKeyDetector detector = new BigKeyDetector(List.of(incomplete, SET_SMALL));

      final List<KeySizeEntry> bigKeys = detector.getBigKeys(10);

      assertThat(bigKeys).hasSize(1);
      assertThat(bigKeys.get(0).key()).isEqualTo("key1");
    }
  }

  @Nested
  @DisplayName("KeySizeEntry record")
  class KeySizeEntryRecord {

    @Test
    @DisplayName("Should implement Comparable correctly")
    void shouldImplementComparable() {
      final KeySizeEntry large = new KeySizeEntry("key1", "SET", 1000);
      final KeySizeEntry small = new KeySizeEntry("key2", "SET", 10);

      assertThat(large.compareTo(small)).isLessThan(0); // descending order
      assertThat(small.compareTo(large)).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should handle equal sizes")
    void shouldHandleEqualSizes() {
      final KeySizeEntry entry1 = new KeySizeEntry("key1", "SET", 100);
      final KeySizeEntry entry2 = new KeySizeEntry("key2", "SET", 100);

      assertThat(entry1.compareTo(entry2)).isEqualTo(0);
    }

    @Test
    @DisplayName("Should be sortable in collections")
    void shouldBeSortableInCollections() {
      final List<KeySizeEntry> list =
          List.of(
              new KeySizeEntry("k1", "SET", 10),
              new KeySizeEntry("k2", "SET", 50),
              new KeySizeEntry("k3", "SET", 30));

      final List<KeySizeEntry> sorted = list.stream().sorted().toList();

      assertThat(sorted.get(0).approximateSizeBytes()).isEqualTo(50);
      assertThat(sorted.get(1).approximateSizeBytes()).isEqualTo(30);
      assertThat(sorted.get(2).approximateSizeBytes()).isEqualTo(10);
    }
  }
}
