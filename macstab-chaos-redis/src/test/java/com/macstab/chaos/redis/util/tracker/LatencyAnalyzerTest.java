/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LatencyAnalyzer}.
 */
@DisplayName("LatencyAnalyzer")
class LatencyAnalyzerTest {

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw for null capturedCommands")
    void shouldThrowForNullCommands() {
      assertThatThrownBy(() -> new LatencyAnalyzer(null, new HashMap<>()))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw for null commandLatencies")
    void shouldThrowForNullLatencies() {
      assertThatThrownBy(() -> new LatencyAnalyzer(List.of(), null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("getAverageLatency()")
  class GetAverageLatency {

    @Test
    @DisplayName("Should return ZERO for empty commands")
    void shouldReturnZeroForEmpty() {
      final LatencyAnalyzer analyzer = new LatencyAnalyzer(List.of(), Map.of());
      assertThat(analyzer.getAverageLatency("GET")).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Should return ZERO for single command (no delta)")
    void shouldReturnZeroForSingleCommand() {
      final String line = "1234.0 [0 127.0.0.1] \"GET\" \"k\"";
      final Map<String, Long> latencies = Map.of(line, 1_234_000_000L);
      final LatencyAnalyzer analyzer = new LatencyAnalyzer(List.of(line), latencies);
      assertThat(analyzer.getAverageLatency("GET")).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Should calculate average delta from timestamps")
    void shouldCalculateAverageDelta() {
      // Two GET commands 1000 microseconds apart
      final String line1 = "1000.000000 [0 127.0.0.1] \"GET\" \"k1\"";
      final String line2 = "1000.001000 [0 127.0.0.1] \"GET\" \"k2\"";
      final Map<String, Long> latencies = new HashMap<>();
      latencies.put(line1, 1_000_000_000L);    // 1000s in micros
      latencies.put(line2, 1_000_001_000L);    // 1000.001s in micros (1000us later)

      final LatencyAnalyzer analyzer =
          new LatencyAnalyzer(List.of(line1, line2), latencies);

      final Duration avg = analyzer.getAverageLatency("GET");
      assertThat(avg).isEqualTo(Duration.ofNanos(1000 * 1000)); // 1000us = 1ms
    }

    @Test
    @DisplayName("Should throw for null command")
    void shouldThrowForNullCommand() {
      final LatencyAnalyzer analyzer = new LatencyAnalyzer(List.of(), Map.of());
      assertThatThrownBy(() -> analyzer.getAverageLatency(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should return ZERO for command not in latency map")
    void shouldReturnZeroWhenNotInLatencyMap() {
      final String line1 = "1234.0 [0 127.0.0.1] \"GET\" \"k1\"";
      final String line2 = "1234.1 [0 127.0.0.1] \"GET\" \"k2\"";
      // No entries in latency map
      final LatencyAnalyzer analyzer =
          new LatencyAnalyzer(List.of(line1, line2), Map.of());
      assertThat(analyzer.getAverageLatency("GET")).isEqualTo(Duration.ZERO);
    }
  }

  @Nested
  @DisplayName("recordLatency()")
  class RecordLatency {

    @Test
    @DisplayName("Should record timestamp from MONITOR line")
    void shouldRecordTimestamp() {
      final String line = "1234.567890 [0 127.0.0.1:1234] \"GET\" \"key\"";
      final Map<String, Long> latencies = new ConcurrentHashMap<>();

      LatencyAnalyzer.recordLatency(line, latencies);

      assertThat(latencies).containsKey(line);
      // 1234.567890 seconds = 1,234,567,890 microseconds
      assertThat(latencies.get(line)).isCloseTo(1_234_567_890L, org.assertj.core.data.Offset.offset(1L));
    }

    @Test
    @DisplayName("Should ignore line without bracket")
    void shouldIgnoreLineWithoutBracket() {
      final Map<String, Long> latencies = new ConcurrentHashMap<>();
      LatencyAnalyzer.recordLatency("no bracket here", latencies);
      assertThat(latencies).isEmpty();
    }

    @Test
    @DisplayName("Should ignore malformed timestamp")
    void shouldIgnoreMalformedTimestamp() {
      final Map<String, Long> latencies = new ConcurrentHashMap<>();
      LatencyAnalyzer.recordLatency("NOT_A_NUMBER [0 127.0.0.1] \"GET\" \"k\"", latencies);
      assertThat(latencies).isEmpty();
    }
  }
}
