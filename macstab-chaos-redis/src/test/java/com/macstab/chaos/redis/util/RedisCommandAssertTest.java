/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.util.RedisCommandTracker;
import com.macstab.chaos.redis.util.assertion.RedisCommandAssert;
import com.macstab.chaos.redis.util.assertion.CommandCountAssert;
import com.macstab.chaos.redis.util.assertion.RatioAssert;

/** Comprehensive unit tests for {@link RedisCommandAssert}. */
@DisplayName("RedisCommandAssert")
class RedisCommandAssertTest {

  private static final String GET_KEY1 = "1234.0 [0 127.0.0.1:12345] \"GET\" \"key:1\"";
  private static final String GET_KEY2 = "1234.1 [0 127.0.0.1:12345] \"GET\" \"key:2\"";
  private static final String SET_KEY1 = "1234.2 [0 127.0.0.1:12345] \"SET\" \"key:1\" \"val\"";
  private static final String SET_KEY2 = "1234.3 [0 127.0.0.1:12345] \"SET\" \"key:2\" \"val\"";
  private static final String KEYS_CMD = "1234.4 [0 127.0.0.1:12345] \"KEYS\" \"*\"";
  private static final String FLUSHALL_CMD = "1234.5 [0 127.0.0.1:12345] \"FLUSHALL\"";
  private static final String HGET_KEY1 =
      "1234.6 [0 127.0.0.1:12345] \"HGET\" \"hash:1\" \"field\"";
  private static final String PING_CMD = "1234.7 [0 127.0.0.1:12345] \"PING\"";

  private static RedisCommandTracker trackerWith(final String... lines) {
    return new RedisCommandTracker(List.of(lines));
  }

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null tracker")
    void shouldThrowForNullTracker() {
      // ARRANGE / ACT / ASSERT
      assertThatThrownBy(() -> new RedisCommandAssert(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("tracker");
    }
  }

  @Nested
  @DisplayName("hasNoCommand()")
  class HasNoCommand {

    @Test
    @DisplayName("Should pass when command count is zero")
    void shouldPassWhenZero() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(SET_KEY1, HGET_KEY1);

      // ACT / ASSERT
      tracker.assertThat().hasNoCommand("GET");
    }

    @Test
    @DisplayName("Should fail when command is present")
    void shouldFailWhenPresent() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasNoCommand("GET"))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected no GET commands but found 2");
    }

    @Test
    @DisplayName("Should throw NPE for null command")
    void shouldThrowForNullCommand() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasNoCommand(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("hasNoDangerousCommands()")
  class HasNoDangerousCommands {

    @Test
    @DisplayName("Should pass when no dangerous commands present")
    void shouldPassWhenNoDangerous() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, SET_KEY1, HGET_KEY1);

      // ACT / ASSERT
      tracker.assertThat().hasNoDangerousCommands();
    }

    @Test
    @DisplayName("Should fail when KEYS command is present")
    void shouldFailWhenKeysPresent() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, KEYS_CMD);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasNoDangerousCommands())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("KEYS");
    }

    @Test
    @DisplayName("Should fail when FLUSHALL command is present")
    void shouldFailWhenFlushallPresent() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(SET_KEY1, FLUSHALL_CMD);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasNoDangerousCommands())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("FLUSHALL");
    }

    @Test
    @DisplayName("Should fail when multiple dangerous commands present")
    void shouldFailWhenMultipleDangerous() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(KEYS_CMD, FLUSHALL_CMD);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasNoDangerousCommands())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("KEYS")
          .hasMessageContaining("FLUSHALL");
    }

    @Test
    @DisplayName("Should pass when only safe commands present")
    void shouldPassWhenOnlySafe() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, SET_KEY1, PING_CMD);

      // ACT / ASSERT
      tracker.assertThat().hasNoDangerousCommands();
    }
  }

  @Nested
  @DisplayName("hasCommand().atLeast()")
  class HasCommandAtLeast {

    @Test
    @DisplayName("Should pass when count equals threshold")
    void shouldPassWhenEqual() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2);

      // ACT / ASSERT
      tracker.assertThat().hasCommand("GET").atLeast(2);
    }

    @Test
    @DisplayName("Should pass when count exceeds threshold")
    void shouldPassWhenExceeds() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2);

      // ACT / ASSERT
      tracker.assertThat().hasCommand("GET").atLeast(1);
    }

    @Test
    @DisplayName("Should fail when count is below threshold")
    void shouldFailWhenBelow() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasCommand("GET").atLeast(3))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected at least 3 GET commands but found 1");
    }
  }

  @Nested
  @DisplayName("hasCommand().atMost()")
  class HasCommandAtMost {

    @Test
    @DisplayName("Should pass when count equals threshold")
    void shouldPassWhenEqual() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2);

      // ACT / ASSERT
      tracker.assertThat().hasCommand("GET").atMost(2);
    }

    @Test
    @DisplayName("Should pass when count is below threshold")
    void shouldPassWhenBelow() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1);

      // ACT / ASSERT
      tracker.assertThat().hasCommand("GET").atMost(5);
    }

    @Test
    @DisplayName("Should fail when count exceeds threshold")
    void shouldFailWhenExceeds() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasCommand("GET").atMost(1))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected at most 1 GET commands but found 2");
    }
  }

  @Nested
  @DisplayName("hasCommand().exactly()")
  class HasCommandExactly {

    @Test
    @DisplayName("Should pass when count matches exactly")
    void shouldPassWhenExact() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2);

      // ACT / ASSERT
      tracker.assertThat().hasCommand("GET").exactly(2);
    }

    @Test
    @DisplayName("Should fail when count differs")
    void shouldFailWhenDifferent() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasCommand("GET").exactly(3))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected exactly 3 GET commands but found 1");
    }
  }

  @Nested
  @DisplayName("hasCommand().never()")
  class HasCommandNever {

    @Test
    @DisplayName("Should pass when command count is zero")
    void shouldPassWhenZero() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(SET_KEY1, SET_KEY2);

      // ACT / ASSERT
      tracker.assertThat().hasCommand("GET").never();
    }

    @Test
    @DisplayName("Should fail when command is present")
    void shouldFailWhenPresent() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasCommand("GET").never())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected exactly 0 GET commands but found 1");
    }
  }

  @Nested
  @DisplayName("hasReadWriteRatio().greaterThan()")
  class HasReadWriteRatioGreaterThan {

    @Test
    @DisplayName("Should pass when ratio exceeds threshold")
    void shouldPassWhenExceeds() {
      // ARRANGE: 2 reads, 1 write = ratio 2.0
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2, SET_KEY1);

      // ACT / ASSERT
      tracker.assertThat().hasReadWriteRatio().greaterThan(1.0);
    }

    @Test
    @DisplayName("Should fail when ratio is below threshold")
    void shouldFailWhenBelow() {
      // ARRANGE: 1 read, 2 writes = ratio 0.5
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, SET_KEY1, SET_KEY2);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasReadWriteRatio().greaterThan(1.0))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("Should handle positive infinity when no writes")
    void shouldHandlePositiveInfinity() {
      // ARRANGE: only reads, no writes
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2);

      // ACT / ASSERT
      tracker.assertThat().hasReadWriteRatio().greaterThan(1000.0);
    }
  }

  @Nested
  @DisplayName("hasReadWriteRatio().lessThan()")
  class HasReadWriteRatioLessThan {

    @Test
    @DisplayName("Should pass when ratio is below threshold")
    void shouldPassWhenBelow() {
      // ARRANGE: 1 read, 2 writes = ratio 0.5
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, SET_KEY1, SET_KEY2);

      // ACT / ASSERT
      tracker.assertThat().hasReadWriteRatio().lessThan(1.0);
    }

    @Test
    @DisplayName("Should fail when ratio exceeds threshold")
    void shouldFailWhenExceeds() {
      // ARRANGE: 2 reads, 1 write = ratio 2.0
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2, SET_KEY1);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasReadWriteRatio().lessThan(1.0))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  @DisplayName("hasReadWriteRatio().between()")
  class HasReadWriteRatioBetween {

    @Test
    @DisplayName("Should pass when ratio is within range")
    void shouldPassWhenWithinRange() {
      // ARRANGE: 2 reads, 1 write = ratio 2.0
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2, SET_KEY1);

      // ACT / ASSERT
      tracker.assertThat().hasReadWriteRatio().between(1.5, 3.0);
    }

    @Test
    @DisplayName("Should fail when ratio is below minimum")
    void shouldFailWhenBelowMin() {
      // ARRANGE: 1 read, 2 writes = ratio 0.5
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, SET_KEY1, SET_KEY2);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasReadWriteRatio().between(1.0, 3.0))
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("Should fail when ratio exceeds maximum")
    void shouldFailWhenAboveMax() {
      // ARRANGE: 5 reads, 1 write = ratio 5.0
      final RedisCommandTracker tracker =
          trackerWith(GET_KEY1, GET_KEY2, GET_KEY1, GET_KEY2, GET_KEY1, SET_KEY1);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertThat().hasReadWriteRatio().between(1.0, 3.0))
          .isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  @DisplayName("Chaining")
  class Chaining {

    @Test
    @DisplayName("Should allow multiple assertions to chain and all pass")
    void shouldChainMultipleAssertions() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1, GET_KEY2, SET_KEY1);

      // ACT / ASSERT
      tracker
          .assertThat()
          .hasCommand("GET")
          .atLeast(2)
          .hasCommand("SET")
          .exactly(1)
          .hasNoDangerousCommands()
          .hasReadWriteRatio()
          .greaterThan(1.0);
    }

    @Test
    @DisplayName("Should stop chain on first failure")
    void shouldStopOnFirstFailure() {
      // ARRANGE
      final RedisCommandTracker tracker = trackerWith(GET_KEY1);

      // ACT / ASSERT
      assertThatThrownBy(
              () -> tracker.assertThat().hasCommand("GET").atLeast(5).hasCommand("SET").exactly(1))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Expected at least 5 GET commands but found 1");
    }
  }
}
