/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.macstab.chaos.redis.util.inspector.model.MemorySnapshot;

import io.lettuce.core.api.sync.RedisCommands;

/** Comprehensive unit tests for {@link MemorySnapshotAnalyzer}. */
@DisplayName("MemorySnapshotAnalyzer")
@ExtendWith(MockitoExtension.class)
class MemorySnapshotAnalyzerTest {

  @Mock RedisCommands<String, String> redisCommands;

  private static String infoMemory(
      final long usedMemory, final long peakMemory, final double fragRatio) {
    return String.format(
        "# Memory\r\nused_memory:%d\r\nused_memory_peak:%d\r\nmem_fragmentation_ratio:%.2f\r\n",
        usedMemory, peakMemory, fragRatio);
  }

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null executor")
    void shouldThrowForNullExecutor() {
      // ARRANGE / ACT / ASSERT
      assertThatThrownBy(() -> new MemorySnapshotAnalyzer(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("snapshot() / getSnapshot()")
  class SnapshotAndGetSnapshot {

    @Test
    @DisplayName("Should throw ISE when getSnapshot called before snapshot")
    void shouldThrowWhenNoSnapshot() {
      // ARRANGE
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> analyzer.getSnapshot())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("snapshot()");
    }

    @Test
    @DisplayName("Should return snapshot after calling snapshot")
    void shouldReturnSnapshotAfterCalling() {
      // ARRANGE
      when(redisCommands.info("memory")).thenReturn(infoMemory(1024000, 2048000, 1.5));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT
      analyzer.snapshot();
      final MemorySnapshot snapshot = analyzer.getSnapshot();

      // ASSERT
      assertThat(snapshot.usedMemoryBytes()).isEqualTo(1024000);
    }
  }

  @Nested
  @DisplayName("getCurrent()")
  class GetCurrent {

    @Test
    @DisplayName("Should call info memory and return parsed snapshot")
    void shouldReturnCurrentSnapshot() {
      // ARRANGE
      when(redisCommands.info("memory")).thenReturn(infoMemory(512000, 1024000, 1.2));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT
      final MemorySnapshot current = analyzer.getCurrent();

      // ASSERT
      assertThat(current.usedMemoryBytes()).isEqualTo(512000);
      assertThat(current.usedMemoryPeakBytes()).isEqualTo(1024000);
      assertThat(current.fragmentationRatio()).isEqualTo(1.2);
    }

    @Test
    @DisplayName("Should return different values on each call")
    void shouldReturnDifferentValues() {
      // ARRANGE
      when(redisCommands.info("memory"))
          .thenReturn(infoMemory(100000, 200000, 1.0))
          .thenReturn(infoMemory(150000, 250000, 1.1));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT
      final MemorySnapshot first = analyzer.getCurrent();
      final MemorySnapshot second = analyzer.getCurrent();

      // ASSERT
      assertThat(first.usedMemoryBytes()).isEqualTo(100000);
      assertThat(second.usedMemoryBytes()).isEqualTo(150000);
    }
  }

  @Nested
  @DisplayName("getMemoryDelta()")
  class GetMemoryDelta {

    @Test
    @DisplayName("Should throw ISE when called before snapshot")
    void shouldThrowWhenNoSnapshot() {
      // ARRANGE
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> analyzer.getMemoryDelta())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("snapshot()");
    }

    @Test
    @DisplayName("Should return positive delta when current exceeds snapshot")
    void shouldReturnPositiveDelta() {
      // ARRANGE
      when(redisCommands.info("memory"))
          .thenReturn(infoMemory(100000, 200000, 1.0))
          .thenReturn(infoMemory(150000, 250000, 1.1));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);
      analyzer.snapshot();

      // ACT
      final long delta = analyzer.getMemoryDelta();

      // ASSERT
      assertThat(delta).isEqualTo(50000);
    }

    @Test
    @DisplayName("Should return negative delta when current is less than snapshot")
    void shouldReturnNegativeDelta() {
      // ARRANGE
      when(redisCommands.info("memory"))
          .thenReturn(infoMemory(200000, 300000, 1.0))
          .thenReturn(infoMemory(150000, 300000, 1.0));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);
      analyzer.snapshot();

      // ACT
      final long delta = analyzer.getMemoryDelta();

      // ASSERT
      assertThat(delta).isEqualTo(-50000);
    }

    @Test
    @DisplayName("Should return zero delta when equal")
    void shouldReturnZeroDelta() {
      // ARRANGE
      when(redisCommands.info("memory")).thenReturn(infoMemory(100000, 200000, 1.0));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);
      analyzer.snapshot();

      // ACT
      final long delta = analyzer.getMemoryDelta();

      // ASSERT
      assertThat(delta).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("assertNoMemoryLeak()")
  class AssertNoMemoryLeak {

    @Test
    @DisplayName("Should throw ISE when called before snapshot")
    void shouldThrowWhenNoSnapshot() {
      // ARRANGE
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> analyzer.assertNoMemoryLeak(0))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("snapshot()");
    }

    @Test
    @DisplayName("Should pass when delta is zero with tolerance zero")
    void shouldPassWhenZeroDelta() {
      // ARRANGE
      when(redisCommands.info("memory")).thenReturn(infoMemory(100000, 200000, 1.0));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);
      analyzer.snapshot();

      // ACT / ASSERT
      analyzer.assertNoMemoryLeak(0);
    }

    @Test
    @DisplayName("Should pass when delta is within tolerance")
    void shouldPassWhenWithinTolerance() {
      // ARRANGE
      when(redisCommands.info("memory"))
          .thenReturn(infoMemory(100000, 200000, 1.0))
          .thenReturn(infoMemory(100100, 200100, 1.0));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);
      analyzer.snapshot();

      // ACT / ASSERT
      analyzer.assertNoMemoryLeak(200);
    }

    @Test
    @DisplayName("Should throw AssertionError when delta exceeds tolerance")
    void shouldThrowWhenExceedsTolerance() {
      // ARRANGE
      when(redisCommands.info("memory"))
          .thenReturn(infoMemory(100000, 200000, 1.0))
          .thenReturn(infoMemory(100500, 200500, 1.0));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);
      analyzer.snapshot();

      // ACT / ASSERT
      assertThatThrownBy(() -> analyzer.assertNoMemoryLeak(100))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("exceeded by 400 bytes");
    }
  }

  @Nested
  @DisplayName("INFO memory parsing")
  class InfoMemoryParsing {

    @Test
    @DisplayName("Should parse full response correctly")
    void shouldParseFullResponse() {
      // ARRANGE
      when(redisCommands.info("memory")).thenReturn(infoMemory(1234567, 2345678, 1.23));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT
      final MemorySnapshot snapshot = analyzer.getCurrent();

      // ASSERT
      assertThat(snapshot.usedMemoryBytes()).isEqualTo(1234567);
      assertThat(snapshot.usedMemoryPeakBytes()).isEqualTo(2345678);
      assertThat(snapshot.fragmentationRatio()).isEqualTo(1.23);
    }

    @Test
    @DisplayName("Should default to zero for missing fields")
    void shouldDefaultToZeroForMissing() {
      // ARRANGE
      when(redisCommands.info("memory")).thenReturn("# Memory\r\n");
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT
      final MemorySnapshot snapshot = analyzer.getCurrent();

      // ASSERT
      assertThat(snapshot.usedMemoryBytes()).isEqualTo(0);
      assertThat(snapshot.usedMemoryPeakBytes()).isEqualTo(0);
      assertThat(snapshot.fragmentationRatio()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should parse partial response with only used_memory")
    void shouldParsePartialResponse() {
      // ARRANGE
      when(redisCommands.info("memory")).thenReturn("# Memory\r\nused_memory:500000\r\n");
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT
      final MemorySnapshot snapshot = analyzer.getCurrent();

      // ASSERT
      assertThat(snapshot.usedMemoryBytes()).isEqualTo(500000);
      assertThat(snapshot.usedMemoryPeakBytes()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("snapshot() twice")
  class SnapshotTwice {

    @Test
    @DisplayName("Should overwrite first snapshot with second")
    void shouldOverwriteFirstSnapshot() {
      // ARRANGE
      when(redisCommands.info("memory"))
          .thenReturn(infoMemory(100000, 200000, 1.0))
          .thenReturn(infoMemory(150000, 250000, 1.1))
          .thenReturn(infoMemory(175000, 275000, 1.15));
      final MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forCommands(redisCommands);

      // ACT
      analyzer.snapshot();
      analyzer.snapshot();
      final long delta = analyzer.getMemoryDelta();

      // ASSERT
      assertThat(delta).isEqualTo(25000);
    }
  }
}
