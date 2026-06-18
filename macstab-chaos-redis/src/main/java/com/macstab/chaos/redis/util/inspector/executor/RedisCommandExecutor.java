/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.executor;

/**
 * Strategy abstraction for executing Redis CLI commands and returning their text output.
 *
 * <p>Decouples inspector tools ({@link com.macstab.chaos.redis.util.inspector.SlowCommandDetector},
 * {@link com.macstab.chaos.redis.util.inspector.ConnectionLeakTracker}, {@link
 * com.macstab.chaos.redis.util.inspector.MemorySnapshotAnalyzer}) from any specific Redis client or
 * container runtime. Callers choose their backend via static factory methods on each inspector
 * class.
 *
 * <p><strong>Available implementations:</strong>
 *
 * <ul>
 *   <li>{@link ShellRedisCommandExecutor} — executes via {@code redis-cli} inside a Testcontainers
 *       container. No Lettuce connection required. Works in DinD, network-isolated, and Podman
 *       environments. Port configurable.
 *   <li>{@link LettuceRedisCommandExecutor} — executes via an existing Lettuce {@code
 *       RedisCommands} connection. Preferred when the caller already manages a connection.
 * </ul>
 *
 * <p><strong>Example (container-backed):</strong>
 *
 * <pre>{@code
 * SlowCommandDetector detector = SlowCommandDetector.forContainer(redisContainer);
 * detector.reset();
 * // ... test ...
 * detector.assertNoSlowCommands(Duration.ofMillis(100));
 * }</pre>
 *
 * <p><strong>Example (Lettuce-backed):</strong>
 *
 * <pre>{@code
 * SlowCommandDetector detector = SlowCommandDetector.forCommands(redisCommands);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public interface RedisCommandExecutor extends AutoCloseable {

  /**
   * Executes a Redis command and returns the text output.
   *
   * <p>The command is a Redis protocol command string (e.g., {@code "SLOWLOG RESET"}, {@code
   * "CLIENT LIST"}, {@code "INFO memory"}).
   *
   * @param redisCommand Redis command to execute — must not be null
   * @return raw text output from Redis (never null, may be empty)
   * @throws RedisCommandExecutionException if execution fails
   */
  String execute(String redisCommand);

  /**
   * Releases any resources owned by this executor.
   *
   * <p>Implementations that do not own resources (e.g., wrapping an externally-managed {@link
   * io.lettuce.core.api.sync.RedisCommands}) must implement this as a no-op. Never throws.
   */
  @Override
  default void close() {
    // no-op by default — override in resource-owning implementations
  }
}
