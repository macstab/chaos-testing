/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Analyzes command latency from MONITOR timestamps.
 *
 * <p><strong>How it works:</strong> MONITOR lines contain microsecond-precision timestamps.
 * This class records those timestamps and computes average time delta between consecutive commands.
 *
 * <p><strong>Note:</strong> MONITOR timestamps measure server-side processing time, not network
 * round-trip time.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * LatencyAnalyzer analyzer = new LatencyAnalyzer(capturedCommands, commandLatencies);
 * Duration avg = analyzer.getAverageLatency("GET");
 * assertThat(avg).isLessThan(Duration.ofMillis(1));
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class LatencyAnalyzer {

  private final List<String> capturedCommands;
  private final Map<String, Long> commandLatencies;

  /**
   * Creates a latency analyzer over the given captured data.
   *
   * @param capturedCommands captured MONITOR lines — must not be null
   * @param commandLatencies map from MONITOR line to timestamp in microseconds — must not be null
   */
  public LatencyAnalyzer(
      final List<String> capturedCommands, final Map<String, Long> commandLatencies) {
    this.capturedCommands = Objects.requireNonNull(capturedCommands, "capturedCommands");
    this.commandLatencies = Objects.requireNonNull(commandLatencies, "commandLatencies");
  }

  /**
   * Returns average latency for a specific command.
   *
   * <p>Calculates average time delta between consecutive occurrences of the command.
   *
   * @param command Redis command name (e.g., "GET") — must not be null
   * @return average latency, or {@link Duration#ZERO} if insufficient data
   */
  public Duration getAverageLatency(final String command) {
    Objects.requireNonNull(command, "command");

    final String quotedCommand = "\"" + command.toUpperCase() + "\"";
    final List<String> matchingLines =
        capturedCommands.stream()
            .filter(line -> line.contains(quotedCommand))
            .collect(Collectors.toList());

    if (matchingLines.isEmpty()) {
      return Duration.ZERO;
    }

    final List<Long> latencies = new ArrayList<>();
    for (int i = 1; i < matchingLines.size(); i++) {
      final Long prev = commandLatencies.get(matchingLines.get(i - 1));
      final Long curr = commandLatencies.get(matchingLines.get(i));
      if (prev != null && curr != null && curr > prev) {
        latencies.add(curr - prev);
      }
    }

    if (latencies.isEmpty()) {
      return Duration.ZERO;
    }

    final long avgMicros = (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);
    return Duration.ofNanos(avgMicros * 1000);
  }

  /**
   * Records latency from a MONITOR output line.
   *
   * <p>Extracts the timestamp from the beginning of the line and stores it keyed by the full line.
   *
   * @param line MONITOR output line with timestamp
   * @param latencies mutable map to write the timestamp into
   */
  public static void recordLatency(final String line, final Map<String, Long> latencies) {
    final int bracketPos = line.indexOf('[');
    if (bracketPos == -1) {
      return;
    }
    try {
      final String timestampStr = line.substring(0, bracketPos).trim();
      final double timestamp = Double.parseDouble(timestampStr);
      final long micros = (long) (timestamp * 1_000_000);
      // Record only if line has a command (has quotes)
      if (line.indexOf('"') != -1) {
        latencies.put(line, micros);
      }
    } catch (final NumberFormatException ignored) {
      // Ignore malformed timestamps
    }
  }
}
