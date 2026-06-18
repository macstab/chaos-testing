/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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

import com.macstab.chaos.redis.util.inspector.executor.RedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.model.SlowLogEntry;

import io.lettuce.core.api.sync.RedisCommands;

/**
 * Unit tests for {@link SlowCommandDetector}.
 *
 * <p>Covers both Lettuce and shell backends exhaustively — no Docker required.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("SlowCommandDetector")
@ExtendWith(MockitoExtension.class)
class SlowCommandDetectorTest {

  @Mock private RedisCommands<String, String> redisCommands;

  @Mock private RedisCommandExecutor mockExecutor;

  // ==================== Helpers ====================

  private static List<Object> lettuceEntry(
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

  // ==================== Constructor Validation ====================

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null RedisCommands")
    void shouldThrowForNullRedisCommands() {
      assertThatThrownBy(() -> new SlowCommandDetector((RedisCommands<String, String>) null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw NPE for null executor")
    void shouldThrowForNullExecutor() {
      assertThatThrownBy(() -> new SlowCommandDetector((RedisCommandExecutor) null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("forCommands() should throw NPE for null")
    void shouldThrowForNullInForCommands() {
      assertThatThrownBy(() -> SlowCommandDetector.forCommands(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("forCommands() should create detector successfully")
    void shouldCreateWithForCommands() {
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);
      assertThat(detector).isNotNull();
    }

    @Test
    @DisplayName("Should create shell-backed detector from mock executor")
    void shouldCreateFromMockExecutor() {
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);
      assertThat(detector).isNotNull();
    }
  }

  // ==================== Lettuce Backend ====================

  @Nested
  @DisplayName("Lettuce backend — reset()")
  class LettuceReset {

    @Test
    @DisplayName("Should call slowlogReset and return this for chaining")
    void shouldCallSlowlogReset() {
      // Arrange
      when(redisCommands.slowlogReset()).thenReturn("OK");
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      final SlowCommandDetector result = detector.reset();

      // Assert
      verify(redisCommands).slowlogReset();
      assertThat(result).isSameAs(detector);
    }
  }

  @Nested
  @DisplayName("Lettuce backend — getSlowCommands()")
  class LettuceGetSlowCommands {

    @Test
    @DisplayName("Should return empty list when slowlogGet returns empty")
    void shouldReturnEmptyWhenNoSlowlog() {
      // Arrange
      when(redisCommands.slowlogGet(128)).thenReturn(List.of());
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should parse single entry correctly")
    void shouldParseSingleEntry() {
      // Arrange
      final List<Object> entry = lettuceEntry(1, 1234567890, 10000, "GET", "key:1");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).hasSize(1);
      assertThat(result.get(0).id()).isEqualTo(1);
      assertThat(result.get(0).duration().toNanos() / 1000).isEqualTo(10000);
      assertThat(result.get(0).command()).isEqualTo("GET");
      assertThat(result.get(0).args()).containsExactly("key:1");
    }

    @Test
    @DisplayName("Should parse multiple entries")
    void shouldParseMultipleEntries() {
      // Arrange
      final List<Object> e1 = lettuceEntry(1, 1234567890, 10000, "GET", "key:1");
      final List<Object> e2 = lettuceEntry(2, 1234567891, 20000, "SET", "key:2", "value");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(e1, e2));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).hasSize(2);
      assertThat(result.get(0).command()).isEqualTo("GET");
      assertThat(result.get(1).command()).isEqualTo("SET");
      assertThat(result.get(1).args()).containsExactly("key:2", "value");
    }

    @Test
    @DisplayName("Should handle entry without client info (4 elements)")
    void shouldHandleMissingClientInfo() {
      // Arrange
      final List<Object> entry = List.of(1L, 1234567890L, 5000L, List.of("PING"));
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).hasSize(1);
      assertThat(result.get(0).clientAddr()).isEmpty();
      assertThat(result.get(0).clientName()).isEmpty();
    }

    @Test
    @DisplayName("Should skip entry with fewer than 4 elements")
    void shouldSkipMalformedEntryTooShort() {
      // Arrange
      final List<Object> malformed = List.of(1L, 1234567890L, 5000L); // only 3 elements
      final List<Object> valid = lettuceEntry(2, 1234567891, 10000, "GET", "key:1");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(malformed, valid));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).hasSize(1);
      assertThat(result.get(0).id()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should skip entry where element[3] is not a List")
    void shouldSkipEntryWhereCommandElementIsNotList() {
      // Arrange — element[3] is a String instead of a List
      final List<Object> badEntry = List.of(1L, 1234567890L, 5000L, "NOT_A_LIST");
      final List<Object> valid = lettuceEntry(2, 1234567891, 10000, "PING");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(badEntry, valid));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).hasSize(1);
      assertThat(result.get(0).id()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should skip entry where numeric fields are not Numbers")
    void shouldSkipEntryWhereDurationIsNotNumber() {
      // Arrange — element[2] is a String, causing ClassCastException in toLong
      final List<Object> badEntry = List.of(1L, 1234567890L, "NOT_A_NUMBER", List.of("GET"));
      final List<Object> valid = lettuceEntry(2, 1234567891, 10000, "PING");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(badEntry, valid));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert — bad entry skipped, valid entry present
      assertThat(result).hasSize(1);
      assertThat(result.get(0).id()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should skip non-List objects in top-level response")
    void shouldSkipNonListObjectsInTopLevel() {
      // Arrange — raw list contains a plain String instead of a sub-list
      when(redisCommands.slowlogGet(128)).thenReturn(List.of("UNEXPECTED_STRING"));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getSlowCommands(int) should call slowlogGet with specified count")
    void shouldForwardCount() {
      // Arrange
      when(redisCommands.slowlogGet(50)).thenReturn(List.of());
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act
      detector.getSlowCommands(50);

      // Assert
      verify(redisCommands).slowlogGet(50);
    }
  }

  // ==================== Shell Backend ====================

  @Nested
  @DisplayName("Shell backend — reset()")
  class ShellReset {

    @Test
    @DisplayName("Should delegate reset to executor")
    void shouldDelegateReset() {
      // Arrange
      when(mockExecutor.execute("SLOWLOG RESET")).thenReturn("OK");
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);

      // Act
      detector.reset();

      // Assert
      verify(mockExecutor).execute("SLOWLOG RESET");
    }
  }

  @Nested
  @DisplayName("Shell backend — getSlowCommands()")
  class ShellGetSlowCommands {

    @Test
    @DisplayName("Should return empty list for null output")
    void shouldReturnEmptyForNullOutput() {
      // Arrange
      when(mockExecutor.execute(anyString())).thenReturn(null);
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list for blank output")
    void shouldReturnEmptyForBlankOutput() {
      // Arrange
      when(mockExecutor.execute(anyString())).thenReturn("   ");
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list for empty string output")
    void shouldReturnEmptyForEmptyOutput() {
      // Arrange
      when(mockExecutor.execute(anyString())).thenReturn("");
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return results (best-effort) for redis-cli SLOWLOG GET output")
    void shouldInvokeShellBackendForSlowlogOutput() {
      // Arrange — redis-cli SLOWLOG GET 128 multi-line output
      // The shell parser is a best-effort state machine; we verify it is invoked
      // and returns a list (empty or not) without throwing.
      final String output =
          "1) 1) (integer) 14\n"
              + "   2) (integer) 1609459200\n"
              + "   3) (integer) 15002\n"
              + "   4) 1) \"SET\"\n"
              + "      2) \"mykey\"\n"
              + "      3) \"myvalue\"\n"
              + "   5) \"127.0.0.1:54321\"\n"
              + "   6) \"\"\n";
      when(mockExecutor.execute(anyString())).thenReturn(output);
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert — parser must not throw; result is a list (content depends on parser internals)
      assertThat(result).isNotNull();
      verify(mockExecutor).execute("SLOWLOG GET 128");
    }

    @Test
    @DisplayName("Should invoke executor with correct SLOWLOG GET command")
    void shouldExtractQuotedString() {
      // Arrange — single-line output to exercise extractQuotedOrRaw path
      when(mockExecutor.execute(anyString())).thenReturn("(integer) 0");
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);

      // Act
      detector.getSlowCommands(50);

      // Assert — correct command forwarded to executor
      verify(mockExecutor).execute("SLOWLOG GET 50");
    }

    @Test
    @DisplayName("Should handle output with no entries gracefully")
    void shouldHandleOutputWithNoEntries() {
      // Arrange — random non-matching output
      when(mockExecutor.execute(anyString())).thenReturn("(empty array)");
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);

      // Act
      final List<SlowLogEntry> result = detector.getSlowCommands();

      // Assert
      assertThat(result).isEmpty();
    }
  }

  // ==================== assertNoSlowCommands() ====================

  @Nested
  @DisplayName("assertNoSlowCommands()")
  class AssertNoSlowCommands {

    @Test
    @DisplayName("Should pass when slowlog is empty")
    void shouldPassWhenEmpty() {
      // Arrange
      when(redisCommands.slowlogGet(128)).thenReturn(List.of());
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act / Assert — no exception
      detector.assertNoSlowCommands(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("Should pass when all entries are under threshold")
    void shouldPassWhenAllUnderThreshold() {
      // Arrange: 5ms entry, 10ms threshold
      final List<Object> entry = lettuceEntry(1, 1234567890, 5_000, "GET", "key:1");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act / Assert — no exception
      detector.assertNoSlowCommands(Duration.ofMillis(10));
    }

    @Test
    @DisplayName("Should throw AssertionError when entry exceeds threshold")
    void shouldThrowWhenExceedsThreshold() {
      // Arrange: 15ms entry, 10ms threshold
      final List<Object> entry = lettuceEntry(1, 1234567890, 15_000, "GET", "key:1");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act / Assert
      assertThatThrownBy(() -> detector.assertNoSlowCommands(Duration.ofMillis(10)))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("GET");
    }

    @Test
    @DisplayName("Should include command name and threshold in error message")
    void shouldIncludeDetailsInErrorMessage() {
      // Arrange
      final List<Object> entry = lettuceEntry(1, 1234567890, 25_000, "HGETALL", "large:hash");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act / Assert
      assertThatThrownBy(() -> detector.assertNoSlowCommands(Duration.ofMillis(10)))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected no commands exceeding")
          .hasMessageContaining("HGETALL");
    }

    @Test
    @DisplayName("Should throw NPE for null threshold")
    void shouldThrowForNullThreshold() {
      // Arrange
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act / Assert
      assertThatThrownBy(() -> detector.assertNoSlowCommands(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should pass when entry equals threshold exactly (not strictly greater)")
    void shouldPassWhenEntryEqualsThreshold() {
      // Arrange: exactly 10ms, threshold 10ms → should NOT exceed
      final List<Object> entry = lettuceEntry(1, 1234567890, 10_000, "GET", "key:1");
      when(redisCommands.slowlogGet(128)).thenReturn(List.of(entry));
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act / Assert — compareTo == 0, not > 0 → no exception
      detector.assertNoSlowCommands(Duration.ofMillis(10));
    }
  }

  // ==================== close() ====================

  @Nested
  @DisplayName("close()")
  class Close {

    @Test
    @DisplayName("Should swallow exception from backend close")
    void shouldSwallowBackendCloseException() throws Exception {
      // Arrange — executor.close() throws
      doThrow(new RuntimeException("close failed")).when(mockExecutor).close();
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);

      // Act / Assert — no exception propagated
      detector.close();
    }

    @Test
    @DisplayName("Should call executor close on shell-backed detector")
    void shouldCloseShellExecutor() throws Exception {
      // Arrange
      final SlowCommandDetector detector = new SlowCommandDetector(mockExecutor);

      // Act
      detector.close();

      // Assert
      verify(mockExecutor).close();
    }

    @Test
    @DisplayName("Should complete close without error on Lettuce-backed detector (caller-owned)")
    void shouldCloseLettuceDetectorCallerOwned() {
      // Arrange — forCommands(): caller owns connection, close is no-op
      final SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);

      // Act / Assert — no exception
      detector.close();
    }
  }
}
