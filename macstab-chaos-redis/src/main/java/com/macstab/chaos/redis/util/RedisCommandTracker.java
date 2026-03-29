/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.util.tracker.CommandParser;
import com.macstab.chaos.redis.util.tracker.CommandWithArgs;
import com.macstab.chaos.redis.util.tracker.KeyPatternMatcher;
import com.macstab.chaos.redis.util.tracker.LatencyAnalyzer;
import com.macstab.chaos.redis.util.tracker.ReplicationLagMeasurer;

import lombok.extern.slf4j.Slf4j;

/**
 * Captures and analyzes Redis MONITOR output from a Testcontainers container.
 *
 * <p><strong>Core capabilities:</strong>
 *
 * <ul>
 *   <li>Command counting and filtering
 *   <li>Key pattern matching (Feature A)
 *   <li>Replication lag measurement (Feature B)
 *   <li>Latency analysis (Feature C)
 *   <li>Command argument extraction (Feature D)
 * </ul>
 *
 * <p><strong>Lifecycle:</strong> Call {@link #start()} before the operation under test,
 * then {@link #stop()} afterwards. The tracker uses a background daemon thread.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * RedisCommandTracker tracker = new RedisCommandTracker(container);
 * tracker.start();
 * redisTemplate.opsForValue().get("user:123");
 * tracker.stop();
 * assertThat(tracker.countCommand("GET")).isEqualTo(1);
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
   * @param lineFilter predicate to filter MONITOR output lines (true = include, false = exclude)
   *     — must not be null
   */
  public RedisCommandTracker(
      final GenericContainer<?> container, final Predicate<String> lineFilter) {
    this.container = Objects.requireNonNull(container, "container");
    this.lineFilter = Objects.requireNonNull(lineFilter, "lineFilter");
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
      throw new RuntimeException("Interrupted while starting MONITOR", e);
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
      final GenericContainer<?> master,
      final GenericContainer<?> replica,
      final Duration timeout) {
    return ReplicationLagMeasurer.measureReplicationLag(master, replica, timeout);
  }

  // ==================== Feature C: Latency/Timing Analysis ====================

  /**
   * Returns average latency for a specific command.
   *
   * @param command Redis command name — must not be null
   * @return average latency or Duration.ZERO if no data
   */
  public Duration getAverageLatency(final String command) {
    return new LatencyAnalyzer(capturedCommands, commandLatencies).getAverageLatency(command);
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

  /**
   * Default filter: includes client commands, excludes replication traffic.
   *
   * @param line MONITOR output line
   * @return true if line is a client command (not replication)
   */
  private static boolean isClientCommand(final String line) {
    return (line.contains("\"GET\"")
            || line.contains("\"SET\"")
            || line.contains("\"HGET\"")
            || line.contains("\"HSET\"")
            || line.contains("\"DEL\"")
            || line.contains("\"INCR\"")
            || line.contains("\"DECR\""))
        && !line.contains(":6379]");
  }

  /**
   * Builder for custom RedisCommandTracker configurations.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * RedisCommandTracker tracker = RedisCommandTracker.builder()
   *     .container(replicaContainer)
   *     .trackCommands(Set.of("GET", "HGETALL"))
   *     .build();
   * }</pre>
   */
  public static final class Builder {
    private GenericContainer<?> container;
    private Set<String> commands = Set.of("GET", "SET", "HGET", "HSET", "DEL", "INCR", "DECR");
    private boolean filterReplication = true;

    /** Sets the Redis container to monitor. */
    public Builder container(final GenericContainer<?> container) {
      this.container = container;
      return this;
    }

    /** Sets which commands to track (case-insensitive). */
    public Builder trackCommands(final Set<String> commands) {
      this.commands = Objects.requireNonNull(commands, "commands");
      return this;
    }

    /** Enables/disables replication traffic filtering. */
    public Builder filterReplication(final boolean filter) {
      this.filterReplication = filter;
      return this;
    }

    /** Builds the RedisCommandTracker. */
    public RedisCommandTracker build() {
      Objects.requireNonNull(container, "container not set");

      final Set<String> trackedCommands = Set.copyOf(commands);
      final boolean filterRep = filterReplication;

      final Predicate<String> filter =
          line -> {
            final boolean hasCommand =
                trackedCommands.stream()
                    .anyMatch(cmd -> line.contains("\"" + cmd.toUpperCase() + "\""));
            if (!hasCommand) {
              return false;
            }
            return !filterRep || !line.contains(":6379]");
          };

      return new RedisCommandTracker(container, filter);
    }
  }

  /**
   * Creates a builder for custom configurations.
   *
   * @return new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }
}
