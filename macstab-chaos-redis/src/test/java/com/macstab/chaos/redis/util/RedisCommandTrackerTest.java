/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.util.tracker.CommandWithArgs;

/**
 * Unit tests for {@link RedisCommandTracker} new features.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("RedisCommandTracker Feature Tests")
class RedisCommandTrackerTest {

  @Nested
  @DisplayName("Feature A: Key Pattern Filtering")
  class KeyPatternFilteringTests {

    @Test
    @DisplayName("Should count commands matching key pattern with wildcards")
    void shouldCountCommandsMatchingKeyPattern() {
      // ARRANGE
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer, line -> true);

      // Simulate captured commands
      tracker.getCapturedCommands().clear();
      addMockCommand(
          tracker, "1234567890.123456 [0 172.17.0.1:54321] \"GET\" \"user:123\""); // Match
      addMockCommand(
          tracker, "1234567890.123457 [0 172.17.0.1:54322] \"GET\" \"user:456\""); // Match
      addMockCommand(
          tracker, "1234567890.123458 [0 172.17.0.1:54323] \"GET\" \"product:789\""); // No match
      addMockCommand(
          tracker, "1234567890.123459 [0 172.17.0.1:54324] \"SET\" \"user:999\" \"data\""); // No
      // match (wrong
      // command)

      // ACT
      final long count = tracker.countCommandsMatchingKeyPattern("GET", "user:*");

      // ASSERT
      assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle single character wildcard (?)")
    void shouldHandleSingleCharWildcard() {
      // ARRANGE
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer, line -> true);

      addMockCommand(tracker, "1234567890.123456 [0 172.17.0.1:54321] \"GET\" \"key:1\"");
      addMockCommand(tracker, "1234567890.123457 [0 172.17.0.1:54322] \"GET\" \"key:2\"");
      addMockCommand(tracker, "1234567890.123458 [0 172.17.0.1:54323] \"GET\" \"key:12\""); // No
      // match
      // (2
      // chars)

      // ACT
      final long count = tracker.countCommandsMatchingKeyPattern("GET", "key:?");

      // ASSERT
      assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Should return commands matching key pattern")
    void shouldReturnCommandsMatchingKeyPattern() {
      // ARRANGE
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer, line -> true);

      addMockCommand(tracker, "1234567890.123456 [0 172.17.0.1:54321] \"GET\" \"cache:data1\"");
      addMockCommand(tracker, "1234567890.123457 [0 172.17.0.1:54322] \"SET\" \"cache:data2\"");
      addMockCommand(tracker, "1234567890.123458 [0 172.17.0.1:54323] \"GET\" \"session:123\"");

      // ACT
      final List<String> cacheCommands = tracker.getCommandsMatchingKeyPattern("cache:*");

      // ASSERT
      assertThat(cacheCommands).hasSize(2);
      assertThat(cacheCommands.get(0)).contains("cache:data1");
      assertThat(cacheCommands.get(1)).contains("cache:data2");
    }

    @Test
    @DisplayName("Should convert glob pattern to regex correctly")
    void shouldConvertGlobToRegex() {
      // Test via reflection or indirect testing
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer, line -> true);

      addMockCommand(tracker, "1234567890.123456 [0 172.17.0.1:54321] \"GET\" \"test.key.123\"");

      // ACT: Pattern with dot should match literal dot
      final long count = tracker.countCommandsMatchingKeyPattern("GET", "test.key.*");

      // ASSERT: Should match (dot is literal, not wildcard)
      assertThat(count).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Feature B: Replication Lag Measurement")
  class ReplicationLagTests {

    @Test
    @DisplayName("Should require non-null master container")
    void shouldRejectNullMaster() {
      final GenericContainer<?> replica = mock(GenericContainer.class);

      assertThatThrownBy(() -> RedisCommandTracker.measureReplicationLag(null, replica))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("master");
    }

    @Test
    @DisplayName("Should require non-null replica container")
    void shouldRejectNullReplica() {
      final GenericContainer<?> master = mock(GenericContainer.class);

      assertThatThrownBy(() -> RedisCommandTracker.measureReplicationLag(master, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("replica");
    }

    @Test
    @DisplayName("Should require non-null timeout")
    void shouldRejectNullTimeout() {
      final GenericContainer<?> master = mock(GenericContainer.class);
      final GenericContainer<?> replica = mock(GenericContainer.class);

      assertThatThrownBy(() -> RedisCommandTracker.measureReplicationLag(master, replica, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("timeout");
    }
  }

  @Nested
  @DisplayName("Feature C: Latency/Timing Analysis")
  class LatencyAnalysisTests {

    @Test
    @DisplayName("Should return zero latency when no commands captured")
    void shouldReturnZeroLatencyForNoCommands() {
      // ARRANGE
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer);

      // ACT
      final Duration latency = tracker.getAverageLatency("GET");

      // ASSERT
      assertThat(latency).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Should calculate average latency from timestamps")
    void shouldCalculateAverageLatency() {
      // ARRANGE
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer, line -> true);

      // Simulate commands with timestamps (microsecond precision)
      addMockCommand(tracker, "1234567890.123456 [0 172.17.0.1:54321] \"GET\" \"key1\"");
      addMockCommand(tracker, "1234567890.125456 [0 172.17.0.1:54321] \"GET\" \"key2\""); // +2ms
      addMockCommand(tracker, "1234567890.126456 [0 172.17.0.1:54321] \"GET\" \"key3\""); // +1ms

      // ACT
      final Duration avgLatency = tracker.getAverageLatency("GET");

      // ASSERT: Average of 2ms and 1ms = 1.5ms
      assertThat(avgLatency.toMillis()).isBetween(1L, 2L);
    }

    @Test
    @DisplayName("Should require non-null command for latency query")
    void shouldRejectNullCommandForLatency() {
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer);

      assertThatThrownBy(() -> tracker.getAverageLatency(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("command");
    }
  }

  @Nested
  @DisplayName("Feature D: Command Arguments Extraction")
  class CommandArgumentsTests {

    @Test
    @DisplayName("Should parse command with arguments")
    void shouldParseCommandWithArguments() {
      // ARRANGE
      final String line = "1234567890.123456 [0 172.17.0.1:54321] \"SET\" \"user:123\" \"john\"";

      // ACT
      final CommandWithArgs cmd = CommandWithArgs.parse(line);

      // ASSERT
      assertThat(cmd).isNotNull();
      assertThat(cmd.getCommand()).isEqualTo("SET");
      assertThat(cmd.getKey()).isEqualTo("user:123");
      assertThat(cmd.getValue()).isEqualTo("john");
      assertThat(cmd.getArgs()).containsExactly("user:123", "john");
    }

    @Test
    @DisplayName("Should parse GET command with single argument")
    void shouldParseGetCommand() {
      // ARRANGE
      final String line = "1234567890.123456 [0 172.17.0.1:54321] \"GET\" \"key:123\"";

      // ACT
      final CommandWithArgs cmd = CommandWithArgs.parse(line);

      // ASSERT
      assertThat(cmd).isNotNull();
      assertThat(cmd.getCommand()).isEqualTo("GET");
      assertThat(cmd.getKey()).isEqualTo("key:123");
      assertThat(cmd.getValue()).isNull(); // GET has no value
    }

    @Test
    @DisplayName("Should parse SET with expiration arguments")
    void shouldParseSetWithExpiration() {
      // ARRANGE
      final String line =
          "1234567890.123456 [0 172.17.0.1:54321] \"SET\" \"key\" \"value\" \"EX\" \"3600\"";

      // ACT
      final CommandWithArgs cmd = CommandWithArgs.parse(line);

      // ASSERT
      assertThat(cmd).isNotNull();
      assertThat(cmd.getCommand()).isEqualTo("SET");
      assertThat(cmd.getArgs()).containsExactly("key", "value", "EX", "3600");
    }

    @Test
    @DisplayName("Should return null for malformed line")
    void shouldReturnNullForMalformedLine() {
      // ARRANGE: Line without quotes
      final String line = "1234567890.123456 [0 172.17.0.1:54321] SET key value";

      // ACT
      final CommandWithArgs cmd = CommandWithArgs.parse(line);

      // ASSERT
      assertThat(cmd).isNull();
    }

    @Test
    @DisplayName("Should extract commands with arguments via tracker")
    void shouldExtractCommandsWithArgumentsViaTracker() {
      // ARRANGE
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer, line -> true);

      addMockCommand(
          tracker, "1234567890.123456 [0 172.17.0.1:54321] \"SET\" \"user:1\" \"alice\"");
      addMockCommand(tracker, "1234567890.123457 [0 172.17.0.1:54322] \"SET\" \"user:2\" \"bob\"");
      addMockCommand(tracker, "1234567890.123458 [0 172.17.0.1:54323] \"GET\" \"user:1\"");

      // ACT
      final List<CommandWithArgs> setCommands = tracker.getCommandsWithArguments("SET");

      // ASSERT
      assertThat(setCommands).hasSize(2);
      assertThat(setCommands.get(0).getKey()).isEqualTo("user:1");
      assertThat(setCommands.get(0).getValue()).isEqualTo("alice");
      assertThat(setCommands.get(1).getKey()).isEqualTo("user:2");
      assertThat(setCommands.get(1).getValue()).isEqualTo("bob");
    }

    @Test
    @DisplayName("Should require non-null command for arguments extraction")
    void shouldRejectNullCommandForArguments() {
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer);

      assertThatThrownBy(() -> tracker.getCommandsWithArguments(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("command");
    }

    @Test
    @DisplayName("CommandWithArgs toString should format correctly")
    void commandWithArgsToStringShouldFormat() {
      // ARRANGE
      final String line = "1234567890.123456 [0 172.17.0.1:54321] \"SET\" \"key\" \"value\"";
      final CommandWithArgs cmd = CommandWithArgs.parse(line);

      // ACT
      final String str = cmd.toString();

      // ASSERT
      assertThat(str).isEqualTo("SET key value");
    }
  }

  @Nested
  @DisplayName("Existing Functionality Tests")
  class ExistingFunctionalityTests {

    @Test
    @DisplayName("Should require non-null container")
    void shouldRejectNullContainer() {
      assertThatThrownBy(() -> new RedisCommandTracker((GenericContainer<?>) null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should require non-null line filter")
    void shouldRejectNullLineFilter() {
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);

      assertThatThrownBy(() -> new RedisCommandTracker(mockContainer, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("lineFilter");
    }

    @Test
    @DisplayName("Should count specific command")
    void shouldCountSpecificCommand() {
      // ARRANGE
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer, line -> true);

      addMockCommand(tracker, "1234567890.123456 [0 172.17.0.1:54321] \"GET\" \"key1\"");
      addMockCommand(tracker, "1234567890.123457 [0 172.17.0.1:54322] \"GET\" \"key2\"");
      addMockCommand(tracker, "1234567890.123458 [0 172.17.0.1:54323] \"SET\" \"key3\" \"value\"");

      // ACT
      final long getCount = tracker.countCommand("GET");
      final long setCount = tracker.countCommand("SET");

      // ASSERT
      assertThat(getCount).isEqualTo(2);
      assertThat(setCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Should require non-null command for counting")
    void shouldRejectNullCommandForCounting() {
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer);

      assertThatThrownBy(() -> tracker.countCommand(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("command");
    }

    @Test
    @DisplayName("Should return captured commands size")
    void shouldReturnSize() {
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer, line -> true);

      addMockCommand(tracker, "1234567890.123456 [0 172.17.0.1:54321] \"GET\" \"key1\"");
      addMockCommand(tracker, "1234567890.123457 [0 172.17.0.1:54322] \"GET\" \"key2\"");

      assertThat(tracker.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should clear captured commands on reset")
    void shouldClearOnReset() {
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);
      final RedisCommandTracker tracker = new RedisCommandTracker(mockContainer, line -> true);

      addMockCommand(tracker, "1234567890.123456 [0 172.17.0.1:54321] \"GET\" \"key1\"");

      tracker.reset();

      assertThat(tracker.size()).isZero();
    }
  }

  @Nested
  @DisplayName("Builder Tests")
  class BuilderTests {

    @Test
    @DisplayName("Builder should require container")
    void builderShouldRequireContainer() {
      assertThatThrownBy(() -> RedisCommandTracker.builder().build())
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container not set");
    }

    @Test
    @DisplayName("Builder should create tracker with custom commands")
    void builderShouldCreateTrackerWithCustomCommands() {
      final GenericContainer<?> mockContainer = mock(GenericContainer.class);

      final RedisCommandTracker tracker =
          RedisCommandTracker.builder()
              .container(mockContainer)
              .trackCommands(java.util.Set.of("HGETALL", "HSET"))
              .build();

      assertThat(tracker).isNotNull();
    }

    @Test
    @DisplayName("Builder should reject null commands")
    void builderShouldRejectNullCommands() {
      assertThatThrownBy(() -> RedisCommandTracker.builder().trackCommands(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("commands");
    }
  }

  // ==================== Helper Methods ====================

  /**
   * Adds a mock command to tracker's captured list (for testing without actual containers).
   *
   * <p>Uses reflection to access the private capturedCommands field since getCapturedCommands()
   * returns a copy.
   *
   * @param tracker tracker instance
   * @param line MONITOR output line
   */
  private static void addMockCommand(final RedisCommandTracker tracker, final String line) {
    try {
      final var field = RedisCommandTracker.class.getDeclaredField("capturedCommands");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      final List<String> commands = (List<String>) field.get(tracker);
      commands.add(line);

      // Also record latency if line has timestamp
      final var latencyField = RedisCommandTracker.class.getDeclaredField("commandLatencies");
      latencyField.setAccessible(true);
      @SuppressWarnings("unchecked")
      final java.util.Map<String, Long> latencies =
          (java.util.Map<String, Long>) latencyField.get(tracker);

      // Extract timestamp
      final int bracketPos = line.indexOf('[');
      if (bracketPos > 0) {
        try {
          final String timestampStr = line.substring(0, bracketPos).trim();
          final double timestamp = Double.parseDouble(timestampStr);
          final long micros = (long) (timestamp * 1_000_000);
          latencies.put(line, micros);
        } catch (NumberFormatException e) {
          // Ignore
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to add mock command", e);
    }
  }
}
