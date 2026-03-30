/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.macstab.chaos.redis.util.inspector.SlowCommandDetector;
import com.macstab.chaos.redis.util.inspector.model.SlowLogEntry;

import io.lettuce.core.api.sync.RedisCommands;

/** Comprehensive unit tests for {@link SlowCommandDetector}. */
@DisplayName("SlowCommandDetector")
@ExtendWith(MockitoExtension.class)
class SlowCommandDetectorTest {

  @Mock RedisCommands<String, String> redisCommands;

  private static List<Object> slowlogEntry(
      final long id,
      final long ts,
      final long durationMicros,
      final String command,
      final String... args) {
    final List<Object> cmdList = new ArrayList<>();
    cmdList.add(command);
    cmdList.addAll(List.of(args));
    return List.of(id, ts, durationMicros, cmdList, "127.0.0.1:12345", "");
  }

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null executor")
    void shouldThrowForNullExecutor() {
      // ARRANGE / ACT / ASSERT
      assertThatThrownBy(() -> new SlowCommandDetector((RedisCommands<String, String>) null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw NPE for null redisCommands in forCommands")
    void shouldThrowForNullRedisCommands() {
      // ARRANGE / ACT / ASSERT
      assertThatThrownBy(() -> SlowCommandDetector.forCommands(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should create detector with forCommands")
    void shouldCreateWithForCommands() {
      // ARRANGE / ACT
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ASSERT
      assertThat(detector).isNotNull();
    }
  }

  @Nested
  @DisplayName("reset()")
  class Reset {

    @Test
    @DisplayName("Should call slowlogReset on commands")
    void shouldCallSlowlogReset() {
      // ARRANGE
      when(redisCommands.slowlogReset()).thenReturn("OK");
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT
      final SlowCommandDetector result = detector.reset();

      // ASSERT
      verify(redisCommands).slowlogReset();
      assertThat(result).isSameAs(detector);
    }
  }

  @Nested
  @DisplayName("getSlowCommands()")
  class GetSlowCommands {

    @Test
    @DisplayName("Should return empty list when slowlogGet returns empty")
    void shouldReturnEmptyWhenNoSlowlog() {
      // ARRANGE
      when(redisCommands.slowlogGet(128)).thenReturn(List.of());
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // ASSERT
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should parse single entry correctly")
    void shouldParseSingleEntry() {
      // ARRANGE
      final List<Object> entry = slowlogEntry(1, 1234567890, 10000, "GET", "key:1");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // ASSERT
      assertThat(result).hasSize(1);
      assertThat(result.get(0).id()).isEqualTo(1);
      assertThat(result.get(0).duration().toNanos() / 1000).isEqualTo(10000);
      assertThat(result.get(0).command()).isEqualTo("GET");
      assertThat(result.get(0).args()).containsExactly("key:1");
    }

    @Test
    @DisplayName("Should parse multiple entries")
    void shouldParseMultipleEntries() {
      // ARRANGE
      final List<Object> entry1 = slowlogEntry(1, 1234567890, 10000, "GET", "key:1");
      final List<Object> entry2 = slowlogEntry(2, 1234567891, 20000, "SET", "key:2", "value");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry1, entry2));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // ASSERT
      assertThat(result).hasSize(2);
      assertThat(result.get(0).command()).isEqualTo("GET");
      assertThat(result.get(1).command()).isEqualTo("SET");
      assertThat(result.get(1).args()).containsExactly("key:2", "value");
    }

    @Test
    @DisplayName("Should handle missing client info with empty strings")
    void shouldHandleMissingClientInfo() {
      // ARRANGE: entry with size 4 (missing client info)
      final List<Object> cmdList = List.of("PING");
      final List<Object> entry = List.of(1L, 1234567890L, 5000L, cmdList);
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // ASSERT
      assertThat(result).hasSize(1);
      assertThat(result.get(0).clientAddr()).isEmpty();
      assertThat(result.get(0).clientName()).isEmpty();
    }

    @Test
    @DisplayName("Should skip malformed entry with size less than 4")
    void shouldSkipMalformedEntry() {
      // ARRANGE: malformed entry with only 3 elements
      final List<Object> malformed = List.of(1L, 1234567890L, 5000L);
      final List<Object> valid = slowlogEntry(2, 1234567891, 10000, "GET", "key:1");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(malformed, valid));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // ASSERT
      assertThat(result).hasSize(1);
      assertThat(result.get(0).id()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("getSlowCommands(int count)")
  class GetSlowCommandsWithCount {

    @Test
    @DisplayName("Should call slowlogGet with specified count")
    void shouldCallWithSpecifiedCount() {
      // ARRANGE
      when(redisCommands.slowlogGet(50)).thenReturn(List.of());
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT
      final List<SlowLogEntry> result = detector.getSlowCommands(50);

      // ASSERT
      verify(redisCommands).slowlogGet(50);
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("assertNoSlowCommands()")
  class AssertNoSlowCommands {

    @Test
    @DisplayName("Should pass when slowlog is empty")
    void shouldPassWhenEmpty() {
      // ARRANGE
      when(redisCommands.slowlogGet(128)).thenReturn(List.of());
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT / ASSERT
      detector.assertNoSlowCommands(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("Should pass when all entries are under threshold")
    void shouldPassWhenUnderThreshold() {
      // ARRANGE: 5ms entry, 10ms threshold
      final List<Object> entry = slowlogEntry(1, 1234567890, 5000, "GET", "key:1");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT / ASSERT
      detector.assertNoSlowCommands(Duration.ofMillis(10));
    }

    @Test
    @DisplayName("Should throw AssertionError when entry exceeds threshold")
    void shouldThrowWhenExceedsThreshold() {
      // ARRANGE: 15ms entry, 10ms threshold
      final List<Object> entry = slowlogEntry(1, 1234567890, 15000, "GET", "key:1");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> detector.assertNoSlowCommands(Duration.ofMillis(10)))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("GET")
          .hasMessageContaining("15");
    }

    @Test
    @DisplayName("Should throw NPE for null threshold")
    void shouldThrowForNullThreshold() {
      // ARRANGE
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> detector.assertNoSlowCommands(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("assertNoSlowCommands() — error message")
  class AssertNoSlowCommandsErrorMessage {

    @Test
    @DisplayName("Should include command name and duration in error message")
    void shouldIncludeDetailsInErrorMessage() {
      // ARRANGE
      final List<Object> entry = slowlogEntry(1, 1234567890, 25000, "HGETALL", "large:hash");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> detector.assertNoSlowCommands(Duration.ofMillis(10)))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected no commands exceeding")
          .hasMessageContaining("HGETALL");
    }
  }
}
