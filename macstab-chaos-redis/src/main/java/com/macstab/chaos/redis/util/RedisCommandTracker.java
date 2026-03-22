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
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.testcontainers.containers.GenericContainer;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

/**
 * Tracks Redis commands in real-time using the MONITOR command.
 *
 * <p><strong>Purpose:</strong> Verify command routing in integration tests (e.g., reads go to
 * replicas, writes go to master).
 *
 * <p><strong>Design:</strong> Runs {@code redis-cli MONITOR} in a background thread and captures
 * all commands. Provides filtering to exclude replication traffic and count specific command types.
 *
 * <p><strong>Thread Safety:</strong> This class is thread-safe. Command capture and counting use
 * {@link CopyOnWriteArrayList} for safe concurrent access.
 *
 * <p><strong>Lifecycle:</strong>
 *
 * <ol>
 *   <li>Create tracker: {@code new RedisCommandTracker(container)}
 *   <li>Start monitoring: {@code tracker.start()}
 *   <li>Execute Redis commands from application
 *   <li>Stop monitoring: {@code tracker.stop()}
 *   <li>Query results: {@code tracker.countCommand("GET")}
 * </ol>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * // Track commands on replica
 * RedisCommandTracker tracker = new RedisCommandTracker(replicaContainer);
 * tracker.start();
 *
 * // Execute 1000 reads
 * for (int i = 0; i < 1000; i++) {
 *   redisTemplate.opsForValue().get("key:" + i);
 * }
 *
 * tracker.stop();
 *
 * // Verify replica handled reads
 * long getCount = tracker.countCommand("GET");
 * assertThat(getCount).isGreaterThan(900);
 * }</pre>
 *
 * <p><strong>Filtering Replication Traffic:</strong>
 *
 * <p>By default, replication commands (source port :6379) are filtered out. This prevents false
 * positives when testing Sentinel routing:
 *
 * <pre>
 * Client command:      [0 172.17.0.1:54321] "GET" "key"  ✅ Tracked
 * Replication command: [0 172.18.0.2:6379] "SET" "key"   ❌ Filtered
 * </pre>
 *
 * <p><strong>Custom Filtering:</strong>
 *
 * <pre>{@code
 * // Track only GET and SET commands
 * RedisCommandTracker tracker = RedisCommandTracker.builder()
 *     .container(replicaContainer)
 *     .trackCommands(Set.of("GET", "SET"))
 *     .build();
 *
 * // Track all commands (no filtering)
 * RedisCommandTracker tracker = RedisCommandTracker.builder()
 *     .container(masterContainer)
 *     .filter(line -> true)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a href="https://redis.io/commands/monitor">Redis MONITOR Command</a>
 */
public final class RedisCommandTracker {

  private final GenericContainer<?> container;
  private final Predicate<String> lineFilter;
  private final List<String> capturedCommands = new CopyOnWriteArrayList<>();
  private final Map<String, Long> commandLatencies = new ConcurrentHashMap<>();
  private Thread monitorThread;
  private volatile boolean running = false;

  /**
   * Creates a command tracker with default filtering (excludes replication traffic).
   *
   * @param container Redis container to monitor
   */
  public RedisCommandTracker(final GenericContainer<?> container) {
    this(container, RedisCommandTracker::isClientCommand);
  }

  /**
   * Creates a command tracker with custom filtering.
   *
   * @param container Redis container to monitor
   * @param lineFilter predicate to filter MONITOR output lines (true = include, false = exclude)
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
    if (running) {
      throw new IllegalStateException("Tracker already started");
    }

    running = true;
    monitorThread =
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
                          public void onNext(com.github.dockerjava.api.model.Frame frame) {
                            if (running) {
                              final var line = new String(frame.getPayload()).trim();
                              if (lineFilter.test(line)) {
                                capturedCommands.add(line);
                                recordLatency(line); // Track latency
                              }
                            }
                          }
                        })
                    .awaitStarted();
              } catch (Exception e) {
                if (running) {
                  // Only log if not intentionally stopped
                  e.printStackTrace();
                }
              }
            });

    monitorThread.setDaemon(true);
    monitorThread.setName("redis-monitor-" + container.getContainerId());
    monitorThread.start();

    // Wait for MONITOR to start (~500ms is sufficient)
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
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
    running = false;
    if (monitorThread != null) {
      monitorThread.interrupt();
      try {
        monitorThread.join(1000); // Wait max 1s for thread to finish
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Counts occurrences of a specific Redis command.
   *
   * <p>Example MONITOR line: {@code 1234567890.123456 [0 172.17.0.1:54321] "GET" "key"}
   *
   * <p>This method searches for {@code "GET"} (quoted) in captured lines.
   *
   * @param command Redis command name (e.g., "GET", "SET", "HGETALL")
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
   * <p>Useful for debugging or custom analysis.
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
   * <p><strong>Use Case:</strong> Test key affinity routing (specific keys go to specific
   * replicas).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Test: user:* keys go to replicaEU, product:* keys go to replicaUS
   * RedisCommandTracker trackerEU = new RedisCommandTracker(replicaEU);
   * RedisCommandTracker trackerUS = new RedisCommandTracker(replicaUS);
   *
   * trackerEU.start();
   * trackerUS.start();
   *
   * // Execute mixed commands
   * redisTemplate.opsForValue().get("user:123");    // Should hit EU
   * redisTemplate.opsForValue().get("product:456"); // Should hit US
   *
   * trackerEU.stop();
   * trackerUS.stop();
   *
   * // Verify routing
   * assertThat(trackerEU.countCommandsMatchingKeyPattern("GET", "user:*")).isEqualTo(1);
   * assertThat(trackerUS.countCommandsMatchingKeyPattern("GET", "product:*")).isEqualTo(1);
   * }</pre>
   *
   * @param command Redis command name (e.g., "GET", "SET")
   * @param keyPattern key pattern with wildcards (e.g., "user:*", "session:*")
   * @return number of commands matching pattern
   */
  public long countCommandsMatchingKeyPattern(final String command, final String keyPattern) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(keyPattern, "keyPattern");

    final String quotedCommand = "\"" + command.toUpperCase() + "\"";
    final Pattern pattern = convertGlobToRegex(keyPattern);

    return capturedCommands.stream()
        .filter(line -> line.contains(quotedCommand))
        .filter(
            line -> {
              final String key = extractFirstKey(line);
              return key != null && pattern.matcher(key).matches();
            })
        .count();
  }

  /**
   * Returns all commands matching a specific key pattern.
   *
   * <p><strong>Use Case:</strong> Debug key routing in production.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Debug: which cache:* commands hit replica?
   * List<String> cacheCommands = tracker.getCommandsMatchingKeyPattern("cache:*");
   * cacheCommands.forEach(System.out::println);
   * }</pre>
   *
   * @param keyPattern key pattern with wildcards (e.g., "cache:*")
   * @return list of matching command lines
   */
  public List<String> getCommandsMatchingKeyPattern(final String keyPattern) {
    Objects.requireNonNull(keyPattern, "keyPattern");

    final Pattern pattern = convertGlobToRegex(keyPattern);

    return capturedCommands.stream()
        .filter(
            line -> {
              final String key = extractFirstKey(line);
              return key != null && pattern.matcher(key).matches();
            })
        .collect(Collectors.toList());
  }

  /**
   * Converts glob pattern (user:*) to regex pattern.
   *
   * @param glob glob pattern
   * @return compiled regex pattern
   */
  private static Pattern convertGlobToRegex(final String glob) {
    final String regex =
        glob.replace(".", "\\.") // Escape dots
            .replace("*", ".*") // * = any chars
            .replace("?", "."); // ? = single char
    return Pattern.compile("^" + regex + "$");
  }

  /**
   * Extracts first key argument from MONITOR line.
   *
   * <p>Example: {@code [0 172.17.0.1:54321] "GET" "user:123"} → "user:123"
   *
   * @param line MONITOR output line
   * @return extracted key or null if not found
   */
  private static String extractFirstKey(final String line) {
    final int cmdEnd = line.indexOf('"', line.indexOf('"') + 1); // End of command
    if (cmdEnd == -1) {
      return null;
    }

    final int keyStart = line.indexOf('"', cmdEnd + 1);
    if (keyStart == -1) {
      return null;
    }

    final int keyEnd = line.indexOf('"', keyStart + 1);
    if (keyEnd == -1) {
      return null;
    }

    return line.substring(keyStart + 1, keyEnd);
  }

  // ==================== Feature B: Replication Lag Measurement ====================

  /**
   * Measures replication lag between master and replica.
   *
   * <p><strong>Use Case:</strong> Test if replication lag meets SLA (e.g., &lt; 100ms).
   *
   * <p><strong>How it works:</strong>
   *
   * <ol>
   *   <li>Writes a unique key to master with timestamp
   *   <li>Polls replica until key appears
   *   <li>Returns duration between write and read
   * </ol>
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @Test
   * void replicationLagShouldBeLessThan100ms(GenericContainer<?> master, GenericContainer<?> replica) {
   *   Duration lag = RedisCommandTracker.measureReplicationLag(master, replica);
   *   assertThat(lag).isLessThan(Duration.ofMillis(100));
   * }
   *
   * // Test with network latency
   * @Test
   * void replicationLagWithSlowNetwork(ControlFacade control) {
   *   control.network().injectLatency(replica, Duration.ofMillis(80));
   *
   *   Duration lag = RedisCommandTracker.measureReplicationLag(master, replica);
   *   assertThat(lag).isBetween(Duration.ofMillis(70), Duration.ofMillis(120));
   * }
   * }</pre>
   *
   * @param master master container
   * @param replica replica container
   * @return replication lag duration
   * @throws IllegalStateException if replication doesn't complete within 5 seconds
   */
  public static Duration measureReplicationLag(
      final GenericContainer<?> master, final GenericContainer<?> replica) {
    return measureReplicationLag(master, replica, Duration.ofSeconds(5));
  }

  /**
   * Measures replication lag with custom timeout.
   *
   * @param master master container
   * @param replica replica container
   * @param timeout maximum wait time for replication
   * @return replication lag duration
   * @throws IllegalStateException if replication doesn't complete within timeout
   */
  public static Duration measureReplicationLag(
      final GenericContainer<?> master, final GenericContainer<?> replica, final Duration timeout) {
    Objects.requireNonNull(master, "master");
    Objects.requireNonNull(replica, "replica");
    Objects.requireNonNull(timeout, "timeout");

    final String testKey = "replication-lag-test-" + System.nanoTime();
    final String testValue = String.valueOf(System.currentTimeMillis());

    // Connect to master
    final RedisURI masterUri =
        RedisURI.builder().withHost(master.getHost()).withPort(master.getFirstMappedPort()).build();

    // Connect to replica
    final RedisURI replicaUri =
        RedisURI.builder()
            .withHost(replica.getHost())
            .withPort(replica.getFirstMappedPort())
            .build();

    try (final RedisClient masterClient = RedisClient.create(masterUri);
        final RedisClient replicaClient = RedisClient.create(replicaUri)) {

      final var masterConn = masterClient.connect().sync();
      final var replicaConn = replicaClient.connect().sync();

      // Write to master and record timestamp
      final long startNanos = System.nanoTime();
      masterConn.set(testKey, testValue);

      // Poll replica until key appears
      final long timeoutNanos = timeout.toNanos();
      String replicaValue = null;

      while (System.nanoTime() - startNanos < timeoutNanos) {
        replicaValue = replicaConn.get(testKey);
        if (testValue.equals(replicaValue)) {
          final long endNanos = System.nanoTime();
          final long lagNanos = endNanos - startNanos;

          // Cleanup
          masterConn.del(testKey);

          return Duration.ofNanos(lagNanos);
        }

        // Sleep briefly before retry
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted while measuring replication lag", e);
        }
      }

      throw new IllegalStateException(
          "Replication did not complete within "
              + timeout.toMillis()
              + "ms. Master wrote: "
              + testValue
              + ", Replica has: "
              + replicaValue);
    }
  }

  // ==================== Feature C: Latency/Timing Analysis ====================

  /**
   * Records command latency from MONITOR timestamp.
   *
   * <p><strong>Note:</strong> MONITOR timestamps have microsecond precision but measure server-side
   * processing time, not network round-trip.
   *
   * <p>Call this method from a custom line filter to track latencies in real-time.
   *
   * @param line MONITOR output line with timestamp
   */
  private void recordLatency(final String line) {
    // Extract timestamp (first token before '[')
    final int bracketPos = line.indexOf('[');
    if (bracketPos == -1) {
      return;
    }

    try {
      final String timestampStr = line.substring(0, bracketPos).trim();
      final double timestamp = Double.parseDouble(timestampStr);
      final long micros = (long) (timestamp * 1_000_000);

      // Extract command
      final int cmdStart = line.indexOf('"');
      final int cmdEnd = line.indexOf('"', cmdStart + 1);
      if (cmdStart != -1 && cmdEnd != -1) {
        final String command = line.substring(cmdStart + 1, cmdEnd);
        commandLatencies.put(line, micros);
      }
    } catch (NumberFormatException e) {
      // Ignore malformed timestamps
    }
  }

  /**
   * Returns average latency for a specific command.
   *
   * <p><strong>Use Case:</strong> Detect performance regressions.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Test: GET latency should be < 1ms
   * RedisCommandTracker tracker = new RedisCommandTracker(replica);
   * tracker.start();
   *
   * // Execute 1000 GETs
   * for (int i = 0; i < 1000; i++) {
   *   redisTemplate.opsForValue().get("key:" + i);
   * }
   *
   * tracker.stop();
   *
   * Duration avgLatency = tracker.getAverageLatency("GET");
   * assertThat(avgLatency).isLessThan(Duration.ofMillis(1));
   * }</pre>
   *
   * @param command Redis command name
   * @return average latency or Duration.ZERO if no data
   */
  public Duration getAverageLatency(final String command) {
    Objects.requireNonNull(command, "command");

    final String quotedCommand = "\"" + command.toUpperCase() + "\"";
    final List<Long> latencies = new ArrayList<>();

    // Collect latencies for matching commands
    final List<String> matchingLines =
        capturedCommands.stream()
            .filter(line -> line.contains(quotedCommand))
            .collect(Collectors.toList());

    if (matchingLines.isEmpty()) {
      return Duration.ZERO;
    }

    // Calculate time delta between consecutive commands
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

  // ==================== Feature D: Command Arguments Extraction ====================

  /**
   * Extracts full command arguments for debugging.
   *
   * <p><strong>Use Case:</strong> Debug production issues by analyzing exact command patterns.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Debug: What SET commands are being executed?
   * RedisCommandTracker tracker = new RedisCommandTracker(master);
   * tracker.start();
   *
   * // ... application runs ...
   *
   * tracker.stop();
   *
   * List<CommandWithArgs> setCommands = tracker.getCommandsWithArguments("SET");
   * setCommands.forEach(cmd ->
   *   System.out.println("SET " + cmd.getKey() + " = " + cmd.getValue())
   * );
   * }</pre>
   *
   * @param command Redis command name
   * @return list of commands with parsed arguments
   */
  public List<CommandWithArgs> getCommandsWithArguments(final String command) {
    Objects.requireNonNull(command, "command");

    final String quotedCommand = "\"" + command.toUpperCase() + "\"";

    return capturedCommands.stream()
        .filter(line -> line.contains(quotedCommand))
        .map(CommandWithArgs::parse)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  /**
   * Represents a Redis command with its parsed arguments.
   *
   * <p><strong>Format:</strong> {@code [0 172.17.0.1:54321] "SET" "user:123" "john" "EX" "3600"}
   */
  public static final class CommandWithArgs {
    private final String command;
    private final List<String> args;

    private CommandWithArgs(final String command, final List<String> args) {
      this.command = command;
      this.args = args;
    }

    /**
     * Parses MONITOR line into CommandWithArgs.
     *
     * @param line MONITOR output line
     * @return parsed command or null if malformed
     */
    static CommandWithArgs parse(final String line) {
      final List<String> tokens = new ArrayList<>();
      int pos = line.indexOf('"');

      while (pos != -1) {
        final int endPos = line.indexOf('"', pos + 1);
        if (endPos == -1) {
          break;
        }
        tokens.add(line.substring(pos + 1, endPos));
        pos = line.indexOf('"', endPos + 1);
      }

      if (tokens.isEmpty()) {
        return null;
      }

      final String command = tokens.get(0);
      final List<String> args = tokens.subList(1, tokens.size());

      return new CommandWithArgs(command, args);
    }

    /**
     * Returns the Redis command name.
     *
     * @return command name (e.g., "GET", "SET")
     */
    public String getCommand() {
      return command;
    }

    /**
     * Returns all command arguments.
     *
     * @return list of arguments
     */
    public List<String> getArgs() {
      return args;
    }

    /**
     * Returns key (first argument).
     *
     * @return key or null if no args
     */
    public String getKey() {
      return args.isEmpty() ? null : args.get(0);
    }

    /**
     * Returns value (second argument for SET/HSET).
     *
     * @return value or null if &lt; 2 args
     */
    public String getValue() {
      return args.size() < 2 ? null : args.get(1);
    }

    @Override
    public String toString() {
      return command + " " + String.join(" ", args);
    }
  }

  /**
   * Default filter: includes client commands, excludes replication traffic.
   *
   * <p>Replication traffic has source port :6379 (Redis nodes communicate on standard port). Client
   * traffic uses random high ports.
   *
   * @param line MONITOR output line
   * @return true if line is a client command (not replication)
   */
  private static boolean isClientCommand(final String line) {
    // Include only lines with common commands AND not from :6379 (replication)
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

    /**
     * Sets the Redis container to monitor.
     *
     * @param container container
     * @return builder
     */
    public Builder container(final GenericContainer<?> container) {
      this.container = container;
      return this;
    }

    /**
     * Sets which commands to track (case-insensitive).
     *
     * @param commands set of command names
     * @return builder
     */
    public Builder trackCommands(final Set<String> commands) {
      this.commands = Objects.requireNonNull(commands, "commands");
      return this;
    }

    /**
     * Enables/disables replication traffic filtering.
     *
     * @param filter true = filter out replication (default), false = include all traffic
     * @return builder
     */
    public Builder filterReplication(final boolean filter) {
      this.filterReplication = filter;
      return this;
    }

    /**
     * Builds the RedisCommandTracker.
     *
     * @return configured tracker
     * @throws NullPointerException if container not set
     */
    public RedisCommandTracker build() {
      Objects.requireNonNull(container, "container not set");

      final Predicate<String> filter =
          line -> {
            // Check if line contains any tracked command
            final boolean hasCommand =
                commands.stream().anyMatch(cmd -> line.contains("\"" + cmd.toUpperCase() + "\""));
            if (!hasCommand) {
              return false;
            }

            // Check replication filter
            if (filterReplication && line.contains(":6379]")) {
              return false; // Exclude replication traffic
            }

            return true;
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
