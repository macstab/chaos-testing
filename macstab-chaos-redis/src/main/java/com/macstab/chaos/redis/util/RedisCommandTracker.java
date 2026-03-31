/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.util.assertion.RedisCommandAssert;
import com.macstab.chaos.redis.util.tracker.CommandCategorizer;
import com.macstab.chaos.redis.util.tracker.CommandCategorizer.CommandCategory;
import com.macstab.chaos.redis.util.tracker.CommandParser;
import com.macstab.chaos.redis.util.tracker.CommandWithArgs;
import com.macstab.chaos.redis.util.tracker.KeyPatternMatcher;
import com.macstab.chaos.redis.util.tracker.LatencyAnalyzer;
import com.macstab.chaos.redis.util.tracker.RedisCommandTrackerBuilder;
import com.macstab.chaos.redis.util.tracker.ReplicationLagMeasurer;

import lombok.extern.slf4j.Slf4j;

/**
 * Captures and analyzes Redis MONITOR output from a Testcontainers container.
 *
 * <p>This class is intentionally the primary facade for all MONITOR-based analysis. Its size
 * reflects breadth of delegation, not complexity — every method is ≤10 LOC and delegates to a
 * focused single-responsibility class in the {@code tracker} sub-package.
 *
 * <p><strong>Core capabilities:</strong>
 *
 * <ul>
 *   <li>Command counting and filtering (core)
 *   <li>Key pattern matching — {@link com.macstab.chaos.redis.util.tracker.KeyPatternMatcher}
 *   <li>Replication lag measurement — {@link
 *       com.macstab.chaos.redis.util.tracker.ReplicationLagMeasurer}
 *   <li>Command gap/timing analysis — {@link com.macstab.chaos.redis.util.tracker.LatencyAnalyzer}
 *   <li>Command argument extraction — {@link com.macstab.chaos.redis.util.tracker.CommandParser}
 *   <li>Command categorization (READ/WRITE/ADMIN/…) — {@link
 *       com.macstab.chaos.redis.util.tracker.CommandCategorizer}
 *   <li>Hot key detection — {@link com.macstab.chaos.redis.util.tracker.HotKeyDetector}
 *   <li>Fluent assertions — {@link com.macstab.chaos.redis.util.assertion.RedisCommandAssert}
 * </ul>
 *
 * <p><strong>Lifecycle:</strong> Call {@link #start()} before the operation under test, then {@link
 * #stop()} afterwards. The tracker uses a background daemon thread.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * RedisCommandTracker tracker = new RedisCommandTracker(container);
 * tracker.start();
 * redisTemplate.opsForValue().get("user:123");
 * tracker.stop();
 *
 * tracker.assertThat()
 *     .hasCommand("GET").atLeast(1)
 *     .hasNoDangerousCommands()
 *     .hasReadWriteRatio().greaterThan(1.0);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class RedisCommandTracker {

  private final GenericContainer<?> container;
  private final Predicate<String> lineFilter;
  private final List<String> capturedCommands = new CopyOnWriteArrayList<>();
  private final Map<String, Long> commandLatencies = new ConcurrentHashMap<>();
  private final AtomicReference<Thread> monitorThread = new AtomicReference<>();
  private final AtomicBoolean running = new AtomicBoolean(false);

  /**
   * Creates a command tracker with default filtering (excludes replication traffic).
   *
   * @param container Redis container to monitor — must not be null
   */
  public RedisCommandTracker(final GenericContainer<?> container) {
    this(container, RedisCommandTracker::isClientCommand);
  }

  /**
   * Creates a command tracker with custom filtering.
   *
   * @param container Redis container to monitor — must not be null
   * @param lineFilter predicate to filter MONITOR output lines (true = include, false = exclude) —
   *     must not be null
   */
  public RedisCommandTracker(
      final GenericContainer<?> container, final Predicate<String> lineFilter) {
    this.container = Objects.requireNonNull(container, "container");
    this.lineFilter = Objects.requireNonNull(lineFilter, "lineFilter");
  }

  /**
   * Package-private test constructor for unit testing assertions.
   *
   * @param capturedLines pre-populated command lines for testing
   */
  RedisCommandTracker(final List<String> capturedLines) {
    this.container = null;
    this.lineFilter = line -> true;
    this.capturedCommands.addAll(capturedLines);
  }

  /**
   * Starts Redis MONITOR in background thread.
   *
   * <p>MONITOR output is captured and filtered in real-time. Call {@link #stop()} when done.
   *
   * @throws IllegalStateException if already started
   */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("Tracker already started");
    }

    final Thread thread =
        new Thread(
            () -> {
              try {
                final var exec =
                    container
                        .getDockerClient()
                        .execCreateCmd(container.getContainerId())
                        .withCmd("redis-cli", "MONITOR")
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .exec();

                final var execId = exec.getId();
                container
                    .getDockerClient()
                    .execStartCmd(execId)
                    .exec(
                        new com.github.dockerjava.api.async.ResultCallback.Adapter<
                            com.github.dockerjava.api.model.Frame>() {
                          @Override
                          public void onNext(final com.github.dockerjava.api.model.Frame frame) {
                            if (running.get()) {
                              final var line = new String(frame.getPayload()).trim();
                              if (lineFilter.test(line)) {
                                capturedCommands.add(line);
                                LatencyAnalyzer.recordLatency(line, commandLatencies);
                              }
                            }
                          }
                        })
                    .awaitStarted();
              } catch (final Exception e) {
                if (running.get()) {
                  e.printStackTrace();
                }
              }
            });

    thread.setDaemon(true);
    thread.setName("redis-monitor-" + container.getContainerId());
    thread.start();
    monitorThread.set(thread);

    try {
      Thread.sleep(500);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while starting MONITOR", e);
    }
  }

  /**
   * Stops Redis MONITOR and background thread.
   *
   * <p>After calling stop(), command counts are frozen and can be queried.
   */
  public void stop() {
    running.set(false);
    final Thread thread = monitorThread.get();
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(1000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Counts occurrences of a specific Redis command.
   *
   * @param command Redis command name (e.g., "GET", "SET", "HGETALL") — must not be null
   * @return number of times command appeared
   */
  public long countCommand(final String command) {
    Objects.requireNonNull(command, "command");
    final String quoted = "\"" + command.toUpperCase() + "\"";
    return capturedCommands.stream().filter(line -> line.contains(quoted)).count();
  }

  /**
   * Returns all captured command lines.
   *
   * @return immutable copy of captured lines
   */
  public List<String> getCapturedCommands() {
    return new ArrayList<>(capturedCommands);
  }

  /**
   * Returns number of captured command lines.
   *
   * @return total commands captured
   */
  public int size() {
    return capturedCommands.size();
  }

  /**
   * Clears all captured commands and latency data.
   *
   * <p>Useful when reusing tracker across multiple test phases.
   */
  public void reset() {
    capturedCommands.clear();
    commandLatencies.clear();
  }

  // ==================== Feature A: Key Pattern Filtering ====================

  /**
   * Counts commands matching a specific key pattern.
   *
   * @param command Redis command name (e.g., "GET", "SET") — must not be null
   * @param keyPattern key pattern with wildcards (e.g., "user:*", "session:*") — must not be null
   * @return number of commands matching pattern
   */
  public long countCommandsMatchingKeyPattern(final String command, final String keyPattern) {
    return new KeyPatternMatcher(capturedCommands)
        .countCommandsMatchingKeyPattern(command, keyPattern);
  }

  /**
   * Returns all commands matching a specific key pattern.
   *
   * @param keyPattern key pattern with wildcards (e.g., "cache:*") — must not be null
   * @return list of matching command lines
   */
  public List<String> getCommandsMatchingKeyPattern(final String keyPattern) {
    return new KeyPatternMatcher(capturedCommands).getCommandsMatchingKeyPattern(keyPattern);
  }

  // ==================== Feature B: Replication Lag Measurement ====================

  /**
   * Measures replication lag between master and replica.
   *
   * @param master master container — must not be null
   * @param replica replica container — must not be null
   * @return replication lag duration
   * @throws IllegalStateException if replication doesn't complete within 5 seconds
   */
  public static Duration measureReplicationLag(
      final GenericContainer<?> master, final GenericContainer<?> replica) {
    return ReplicationLagMeasurer.measureReplicationLag(master, replica);
  }

  /**
   * Measures replication lag with custom timeout.
   *
   * @param master master container — must not be null
   * @param replica replica container — must not be null
   * @param timeout maximum wait time for replication — must not be null
   * @return replication lag duration
   * @throws IllegalStateException if replication doesn't complete within timeout
   */
  public static Duration measureReplicationLag(
      final GenericContainer<?> master, final GenericContainer<?> replica, final Duration timeout) {
    return ReplicationLagMeasurer.measureReplicationLag(master, replica, timeout);
  }

  // ==================== Feature C: Command Gap/Timing Analysis ====================

  /**
   * Returns average inter-arrival time (command gap) for a specific command.
   *
   * <p>This measures how frequently the command is being issued (time between consecutive
   * executions), NOT the response latency.
   *
   * @param command Redis command name — must not be null
   * @return average command gap or Duration.ZERO if no data
   */
  public Duration getAverageCommandGap(final String command) {
    return new LatencyAnalyzer(capturedCommands, commandLatencies).getAverageCommandGap(command);
  }

  /**
   * Returns average latency for a specific command.
   *
   * @param command Redis command name — must not be null
   * @return average command gap or Duration.ZERO if no data
   * @deprecated Use {@link #getAverageCommandGap(String)} instead. This method measures
   *     inter-arrival time between commands, not response latency.
   */
  @Deprecated(since = "2.0", forRemoval = true)
  public Duration getAverageLatency(final String command) {
    return getAverageCommandGap(command);
  }

  // ==================== Feature D: Command Arguments Extraction ====================

  /**
   * Extracts full command arguments for debugging.
   *
   * @param command Redis command name — must not be null
   * @return list of commands with parsed arguments
   */
  public List<CommandWithArgs> getCommandsWithArguments(final String command) {
    return new CommandParser(capturedCommands).getCommandsWithArguments(command);
  }

  // ==================== Feature F: Hot Key Detection ====================

  /**
   * Returns top N hottest keys by access count (descending).
   *
   * @param topN number of keys to return
   * @return list of hot keys with access counts (sorted descending)
   */
  public List<com.macstab.chaos.redis.util.tracker.HotKeyDetector.KeyAccessCount> getHotKeys(
      final int topN) {
    return new com.macstab.chaos.redis.util.tracker.HotKeyDetector(capturedCommands)
        .getHotKeys(topN);
  }

  /**
   * Returns keys exceeding a threshold access count.
   *
   * @param threshold minimum access count
   * @return list of hot keys with access counts (sorted descending)
   */
  public List<com.macstab.chaos.redis.util.tracker.HotKeyDetector.KeyAccessCount>
      getHotKeysExceeding(final long threshold) {
    return new com.macstab.chaos.redis.util.tracker.HotKeyDetector(capturedCommands)
        .getKeysExceeding(threshold);
  }

  // ==================== Feature E: Command Categorization ====================

  /**
   * Returns count of READ commands.
   *
   * @return number of read operations captured
   */
  public long getReadCount() {
    return getCommandsByCategory().getOrDefault(CommandCategory.READ, 0L);
  }

  /**
   * Returns count of WRITE commands.
   *
   * @return number of write operations captured
   */
  public long getWriteCount() {
    return getCommandsByCategory().getOrDefault(CommandCategory.WRITE, 0L);
  }

  /**
   * Returns count of ADMIN commands.
   *
   * @return number of admin operations captured
   */
  public long getAdminCount() {
    return getCommandsByCategory().getOrDefault(CommandCategory.ADMIN, 0L);
  }

  /**
   * Returns count of PUBSUB commands.
   *
   * @return number of pub/sub operations captured
   */
  public long getPubSubCount() {
    return getCommandsByCategory().getOrDefault(CommandCategory.PUBSUB, 0L);
  }

  /**
   * Returns count of TRANSACTION commands.
   *
   * @return number of transaction operations captured
   */
  public long getTransactionCount() {
    return getCommandsByCategory().getOrDefault(CommandCategory.TRANSACTION, 0L);
  }

  /**
   * Returns count of SCRIPTING commands.
   *
   * @return number of scripting operations captured
   */
  public long getScriptingCount() {
    return getCommandsByCategory().getOrDefault(CommandCategory.SCRIPTING, 0L);
  }

  /**
   * Returns count of STREAM commands.
   *
   * @return number of stream operations captured
   */
  public long getStreamCount() {
    return getCommandsByCategory().getOrDefault(CommandCategory.STREAM, 0L);
  }

  /**
   * Returns read/write ratio.
   *
   * <p>Returns {@link Double#POSITIVE_INFINITY} if writes==0, 0.0 if reads==0.
   *
   * @return read/write ratio
   */
  public double getReadWriteRatio() {
    final long reads = getReadCount();
    final long writes = getWriteCount();
    if (writes == 0) {
      return reads > 0 ? Double.POSITIVE_INFINITY : 0.0;
    }
    return (double) reads / writes;
  }

  /**
   * Returns command counts grouped by category.
   *
   * @return map of category to count (never null, may be empty)
   */
  public Map<CommandCategory, Long> getCommandsByCategory() {
    final Map<CommandCategory, Long> result = new ConcurrentHashMap<>();
    for (final String line : capturedCommands) {
      final String cmd = extractCommandName(line);
      if (cmd != null) {
        final CommandCategory category = CommandCategorizer.categorize(cmd);
        result.merge(category, 1L, Long::sum);
      }
    }
    return result;
  }

  /**
   * Extracts command name from a MONITOR line.
   *
   * @param line MONITOR output line
   * @return command name (uppercase), or null if not found
   * @see CommandParser#extractCommandName(String)
   */
  private static String extractCommandName(final String line) {
    return CommandParser.extractCommandName(line);
  }

  /**
   * Default filter: includes all client commands, excluding internal replication traffic.
   *
   * @param line MONITOR output line
   * @return true if line is a client command (not replication)
   */
  private static boolean isClientCommand(final String line) {
    // Exclude replication traffic (internal Redis replication uses :6379])
    if (line.contains(":6379]")) {
      return false;
    }
    // Must look like a MONITOR line (has timestamp + client address)
    if (!line.contains("[")) {
      return false;
    }
    // Must contain at least one quoted token (the command)
    return line.contains("\"");
  }

  /**
   * Creates a builder for custom configurations.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * RedisCommandTracker tracker = RedisCommandTracker.builder()
   *     .container(replicaContainer)
   *     .trackCommands(Set.of("GET", "HGETALL"))
   *     .build();
   * }</pre>
   *
   * @return new builder instance
   */
  public static RedisCommandTrackerBuilder builder() {
    return new RedisCommandTrackerBuilder();
  }

  /**
   * Creates a fluent assertion API for this tracker.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * tracker.assertThat()
   *     .hasCommand("GET").atLeast(5)
   *     .hasNoDangerousCommands();
   * }</pre>
   *
   * @return fluent assertion API
   */
  public RedisCommandAssert assertThat() {
    return new RedisCommandAssert(this);
  }
}
