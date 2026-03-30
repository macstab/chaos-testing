/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.util.inspector.executor.LettuceRedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.executor.RedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.executor.ShellRedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.model.SlowLogEntry;

import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

/**
 * Detects slow commands using Redis SLOWLOG.
 *
 * <p>Wraps {@code SLOWLOG GET} and {@code SLOWLOG RESET} with a typed, assertion-friendly API.
 * Stateless — no snapshot required. Safe to reuse across test methods.
 *
 * <p><strong>Two backends, one API:</strong>
 * <ul>
 *   <li>{@link #forContainer(GenericContainer)} — shell-backed via {@code redis-cli} inside the
 *       container. No Lettuce required. Parses SLOWLOG text output.
 *   <li>{@link #forCommands(RedisCommands)} — Lettuce-backed. Uses typed {@code slowlogGet(int)}
 *       for maximum reliability. Preferred when a connection is already available.
 * </ul>
 *
 * <p><strong>Wire format note (Lettuce backend):</strong> Lettuce 6.x returns {@code SLOWLOG GET}
 * as {@code List<Object>} where each entry is a {@code List<Object>}:
 * <pre>
 *   [0] Long    — entry ID
 *   [1] Long    — unix timestamp (seconds)
 *   [2] Long    — duration in microseconds
 *   [3] List    — [commandName, arg1, arg2, ...]
 *   [4] String  — client address (optional, Redis 4.0+)
 *   [5] String  — client name    (optional, Redis 4.0+)
 * </pre>
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * SlowCommandDetector detector = SlowCommandDetector.forContainer(redisContainer);
 * detector.reset();
 * // ... perform operations ...
 * detector.assertNoSlowCommands(Duration.ofMillis(100));
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class SlowCommandDetector implements AutoCloseable {

  /** Default max number of SLOWLOG entries to retrieve. */
  public static final int DEFAULT_SLOWLOG_COUNT = 128;

  /** Default Redis port. */
  public static final int DEFAULT_REDIS_PORT = ShellRedisCommandExecutor.DEFAULT_REDIS_PORT;

  /**
   * Internal strategy for SLOWLOG operations — eliminates nullable fields.
   *
   * <p>Two implementations: {@link LettuceSlowlogBackend} (typed API, maximum reliability)
   * and {@link ShellSlowlogBackend} (text parsing via redis-cli).
   */
  private interface SlowlogBackend {
    void reset();
    List<SlowLogEntry> get(int count);
    void close();
  }

  /** Lettuce-backed SLOWLOG strategy — uses typed {@code slowlogGet(int)}. */
  private static final class LettuceSlowlogBackend implements SlowlogBackend {
    private final RedisCommands<String, String> commands;
    private final LettuceRedisCommandExecutor ownedExecutor; // null if caller owns connection

    LettuceSlowlogBackend(
        final RedisCommands<String, String> commands,
        final LettuceRedisCommandExecutor ownedExecutor) {
      this.commands = commands;
      this.ownedExecutor = ownedExecutor;
    }

    @Override public void reset() { commands.slowlogReset(); }
    @Override public List<SlowLogEntry> get(final int count) {
      return parseLettuceSlowlog(commands.slowlogGet(count));
    }
    @Override public void close() {
      if (ownedExecutor != null) {
        ownedExecutor.close();
      }
    }
  }

  /** Shell-backed SLOWLOG strategy — parses redis-cli text output. */
  private static final class ShellSlowlogBackend implements SlowlogBackend {
    private final RedisCommandExecutor executor;

    ShellSlowlogBackend(final RedisCommandExecutor executor) {
      this.executor = executor;
    }

    @Override public void reset() { executor.execute("SLOWLOG RESET"); }
    @Override public List<SlowLogEntry> get(final int count) {
      return parseShellSlowlog(executor.execute("SLOWLOG GET " + count));
    }
    @Override public void close() { executor.close(); }
  }

  private final SlowlogBackend backend;

  /**
   * Creates a detector using an executor-backed backend.
   *
   * <p>When passed a {@link LettuceRedisCommandExecutor}, uses the typed Lettuce API internally
   * via {@link LettuceSlowlogBackend} for maximum reliability. When passed a
   * {@link ShellRedisCommandExecutor}, uses text parsing via {@link ShellSlowlogBackend}.
   *
   * @param executor command executor — must not be null
   */
  public SlowCommandDetector(final RedisCommandExecutor executor) {
    Objects.requireNonNull(executor, "executor");
    if (executor instanceof final LettuceRedisCommandExecutor lettuceExec) {
      this.backend = new LettuceSlowlogBackend(lettuceExec.getRedisCommands(), lettuceExec);
    } else {
      this.backend = new ShellSlowlogBackend(executor);
    }
  }

  /**
   * Creates a Lettuce-backed detector using an existing connection.
   * Use {@link #forCommands(RedisCommands)} for convenience.
   *
   * @param redisCommands Lettuce sync commands — must not be null
   */
  public SlowCommandDetector(final RedisCommands<String, String> redisCommands) {
    Objects.requireNonNull(redisCommands, "redisCommands");
    this.backend = new LettuceSlowlogBackend(redisCommands, null);
  }

  // ==================== Factory Methods ====================

  /**
   * Creates a Lettuce-backed detector by connecting to the container's mapped Redis port.
   *
   * <p>This is the default and preferred backend — uses typed {@code slowlogGet(int)} for maximum
   * reliability. The detector owns the connection — call {@link #close()} when done, or use
   * try-with-resources.
   *
   * @param container running Redis container — must not be null
   * @return Lettuce-backed detector (owns its connection)
   */
  public static SlowCommandDetector forContainer(final GenericContainer<?> container) {
    return forContainer(container, DEFAULT_REDIS_PORT);
  }

  /**
   * Creates a Lettuce-backed detector connecting to a custom Redis port on the container.
   *
   * @param container running Redis container — must not be null
   * @param port      Redis port inside the container (mapped port resolved automatically)
   * @return Lettuce-backed detector (owns its connection)
   */
  public static SlowCommandDetector forContainer(
      final GenericContainer<?> container, final int port) {
    Objects.requireNonNull(container, "container");
    // Create owned Lettuce executor — detector backend closes it on close()
    final LettuceRedisCommandExecutor ownedExecutor =
        new LettuceRedisCommandExecutor(container.getHost(), container.getMappedPort(port));
    return new SlowCommandDetector(ownedExecutor);
  }

  /**
   * Creates a Lettuce-backed detector using an existing connection.
   *
   * <p>The caller retains ownership — {@link #close()} is a no-op.
   *
   * @param redisCommands Lettuce sync commands — must not be null
   * @return Lettuce-backed detector (does NOT own the connection)
   */
  public static SlowCommandDetector forCommands(final RedisCommands<String, String> redisCommands) {
    return new SlowCommandDetector(redisCommands);
  }

  /**
   * Creates a shell-backed detector for environments where Lettuce is unavailable.
   *
   * <p>Parses SLOWLOG text output from {@code redis-cli SLOWLOG GET}.
   * Works in DinD, network-isolated, and Podman container topologies.
   *
   * @param container running Redis container — must not be null
   * @return shell-backed detector
   */
  public static SlowCommandDetector forContainerShell(final GenericContainer<?> container) {
    return new SlowCommandDetector(new ShellRedisCommandExecutor(container));
  }

  /**
   * Creates a shell-backed detector targeting a custom Redis port.
   *
   * @param container running Redis container — must not be null
   * @param port      Redis port inside the container
   * @return shell-backed detector
   */
  public static SlowCommandDetector forContainerShell(
      final GenericContainer<?> container, final int port) {
    return new SlowCommandDetector(new ShellRedisCommandExecutor(container, port));
  }

  /**
   * Closes the backend, releasing any owned Lettuce connection.
   */
  @Override
  public void close() {
    try {
      backend.close();
    } catch (final Exception e) {
      log.debug("Error closing slowlog backend", e);
    }
  }

  // ==================== API ====================

  /**
   * Resets the SLOWLOG (clears all entries).
   *
   * @return this instance for method chaining
   */
  public SlowCommandDetector reset() {
    backend.reset();
    return this;
  }

  /**
   * Retrieves the last {@value #DEFAULT_SLOWLOG_COUNT} SLOWLOG entries.
   *
   * @return list of slowlog entries (never null, may be empty)
   */
  public List<SlowLogEntry> getSlowCommands() {
    return getSlowCommands(DEFAULT_SLOWLOG_COUNT);
  }

  /**
   * Retrieves up to {@code count} SLOWLOG entries.
   *
   * @param count maximum number of entries to retrieve
   * @return list of slowlog entries (never null, may be empty)
   */
  public List<SlowLogEntry> getSlowCommands(final int count) {
    return backend.get(count);
  }

  /**
   * Asserts that no commands exceed the given duration threshold.
   *
   * @param threshold maximum acceptable command duration — must not be null
   * @throws AssertionError if any command exceeds the threshold
   */
  public void assertNoSlowCommands(final Duration threshold) {
    Objects.requireNonNull(threshold, "threshold");

    final List<SlowLogEntry> exceeding = new ArrayList<>();
    for (final SlowLogEntry entry : getSlowCommands()) {
      if (entry.duration().compareTo(threshold) > 0) {
        exceeding.add(entry);
      }
    }

    if (!exceeding.isEmpty()) {
      final StringBuilder msg = new StringBuilder("Expected no commands exceeding ")
          .append(threshold).append(" but found:\n");
      for (final SlowLogEntry entry : exceeding) {
        msg.append("  - ").append(entry.command())
            .append(" (").append(entry.duration()).append(")\n");
      }
      throw new AssertionError(msg.toString().trim());
    }
  }

  // ==================== Lettuce Parsing ====================

  /**
   * Parses Lettuce's raw {@code List<Object>} SLOWLOG response into typed entries.
   *
   * @param raw raw response from {@code slowlogGet(int)}
   * @return list of parsed entries
   */
  private static List<SlowLogEntry> parseLettuceSlowlog(final List<Object> raw) {
    final List<SlowLogEntry> entries = new ArrayList<>();
    for (final Object obj : raw) {
      if (obj instanceof final List<?> entry) {
        final SlowLogEntry parsed = parseLettuceEntry(entry);
        if (parsed != null) {
          entries.add(parsed);
        }
      }
    }
    return entries;
  }

  private static SlowLogEntry parseLettuceEntry(final List<?> entry) {
    if (entry.size() < 4) {
      log.debug("Skipping malformed SLOWLOG entry: expected >=4 elements, got {}", entry.size());
      return null;
    }
    try {
      final long id = toLong(entry.get(0));
      final long timestampSeconds = toLong(entry.get(1));
      final long durationMicros = toLong(entry.get(2));
      final Duration duration = Duration.ofNanos(durationMicros * 1_000L);

      if (!(entry.get(3) instanceof final List<?> cmdArgs)) {
        log.debug("Skipping SLOWLOG entry {}: element [3] is not a List", id);
        return null;
      }
      final String command = cmdArgs.isEmpty() ? "" : String.valueOf(cmdArgs.get(0));
      final List<String> args = new ArrayList<>(cmdArgs.size());
      for (int i = 1; i < cmdArgs.size(); i++) {
        args.add(String.valueOf(cmdArgs.get(i)));
      }

      final String clientAddr = entry.size() > 4 ? String.valueOf(entry.get(4)) : "";
      final String clientName = entry.size() > 5 ? String.valueOf(entry.get(5)) : "";

      return new SlowLogEntry(id, timestampSeconds, duration, command, args, clientAddr, clientName);
    } catch (final ClassCastException | NullPointerException e) {
      log.debug("Failed to parse SLOWLOG entry: {}", e.getMessage());
      return null;
    }
  }

  private static long toLong(final Object obj) {
    if (obj instanceof final Number n) {
      return n.longValue();
    }
    throw new ClassCastException(
        "Expected Number but got: " + (obj == null ? "null" : obj.getClass().getName()));
  }

  // ==================== Shell Parsing ====================

  /**
   * Parses {@code redis-cli SLOWLOG GET} text output into typed entries.
   *
   * <p>redis-cli formats SLOWLOG GET as a numbered list:
   * <pre>
   *  1) 1) (integer) 14          -- id
   *     2) (integer) 1609459200  -- timestamp
   *     3) (integer) 15002       -- duration µs
   *     4) 1) "SET"              -- command
   *        2) "mykey"
   *        3) "myvalue"
   *     5) "127.0.0.1:54321"    -- client addr
   *     6) ""                   -- client name
   * </pre>
   * Best-effort parsing — entries with unexpected format are skipped with a debug log.
   *
   * @param output raw redis-cli stdout
   * @return list of parsed entries (never null)
   */
  // Intentionally >40 LOC: this is a single-pass state machine over redis-cli SLOWLOG output.
  // Splitting would scatter the state transitions across methods and reduce readability.
  private static List<SlowLogEntry> parseShellSlowlog(final String output) {
    final List<SlowLogEntry> entries = new ArrayList<>();
    if (output == null || output.isBlank()) {
      return entries;
    }

    // Split by top-level numbered entries: lines starting with " N) 1)"
    final String[] lines = output.split("\n");
    Long id = null;
    Long timestampSeconds = null;
    Long durationMicros = null;
    final List<String> cmdParts = new ArrayList<>();
    String clientAddr = "";
    String clientName = "";
    int fieldIndex = 0; // which top-level field we are in

    for (final String rawLine : lines) {
      final String line = rawLine.trim();

      // Top-level entry start: "N) 1) (integer) <id>"
      if (line.matches("\\d+\\).*")) {
        // Save previous entry if complete
        if (id != null && timestampSeconds != null && durationMicros != null && !cmdParts.isEmpty()) {
          entries.add(buildShellEntry(id, timestampSeconds, durationMicros,
              cmdParts, clientAddr, clientName));
        }
        // Reset state
        id = null; timestampSeconds = null; durationMicros = null;
        cmdParts.clear(); clientAddr = ""; clientName = ""; fieldIndex = 0;

        // Parse the first sub-field on this line
        final Long val = extractInteger(line);
        if (val != null) { id = val; fieldIndex = 1; }
        continue;
      }

      // Sub-fields within an entry: "   N) ..."
      if (line.matches("\\d+\\).*") || line.startsWith("   ") || line.startsWith("\t")) {
        final String stripped = line.replaceAll("^\\d+\\)\\s*", "").trim();
        switch (fieldIndex) {
          case 1 -> { timestampSeconds = extractInteger(stripped); fieldIndex = 2; }
          case 2 -> { durationMicros = extractInteger(stripped); fieldIndex = 3; }
          case 3 -> { cmdParts.add(extractQuotedOrRaw(stripped)); }
          case 4 -> { clientAddr = extractQuotedOrRaw(stripped); fieldIndex = 5; }
          case 5 -> { clientName = extractQuotedOrRaw(stripped); fieldIndex = 6; }
          default -> { /* extra fields, ignore */ }
        }
      }
    }

    // Final entry
    if (id != null && timestampSeconds != null && durationMicros != null && !cmdParts.isEmpty()) {
      entries.add(buildShellEntry(id, timestampSeconds, durationMicros,
          cmdParts, clientAddr, clientName));
    }

    return entries;
  }

  private static SlowLogEntry buildShellEntry(
      final long id, final long ts, final long durationMicros,
      final List<String> cmdParts, final String clientAddr, final String clientName) {
    final String command = cmdParts.isEmpty() ? "" : cmdParts.get(0);
    final List<String> args = cmdParts.size() > 1 ? cmdParts.subList(1, cmdParts.size()) : List.of();
    return new SlowLogEntry(id, ts, Duration.ofNanos(durationMicros * 1_000L),
        command, args, clientAddr, clientName);
  }

  private static Long extractInteger(final String text) {
    final var matcher = java.util.regex.Pattern.compile("\\(integer\\)\\s*(\\d+)").matcher(text);
    if (matcher.find()) {
      try {
        return Long.parseLong(matcher.group(1));
      } catch (final NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static String extractQuotedOrRaw(final String text) {
    if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
      return text.substring(1, text.length() - 1);
    }
    return text;
  }
}
