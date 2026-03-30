/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.util.inspector.executor.LettuceRedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.executor.RedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.executor.ShellRedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.model.MemorySnapshot;

import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

/**
 * Analyzes Redis memory usage over time to detect memory leaks in tests.
 *
 * <p>Takes a baseline snapshot before operations, then compares against current memory to detect
 * unexpected growth. Useful for verifying that write-then-delete test patterns leave no residual
 * memory footprint.
 *
 * <p><strong>Backend:</strong> Works with any container via {@link #forContainer(GenericContainer)}
 * (shell-backed, no Lettuce required), or with an existing Lettuce connection via
 * {@link #forCommands(RedisCommands)}.
 *
 * <p><strong>Lifecycle — one instance per test method:</strong>
 * <pre>
 *   &#64;BeforeEach
 *   void setUp() {
 *       analyzer = MemorySnapshotAnalyzer.forContainer(redisContainer);
 *   }
 * </pre>
 * Do not share instances across test methods. {@link #snapshot()} resets state. Results may vary
 * depending on Redis background operations (AOF rewrite, RDB save) — use a reasonable tolerance.
 *
 * <p><strong>Thread safety:</strong> The snapshot reference is held in an {@link AtomicReference}.
 * Designed for single-threaded test execution only.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * MemorySnapshotAnalyzer analyzer = MemorySnapshotAnalyzer.forContainer(redisContainer);
 * analyzer.snapshot();
 * // ... write and delete keys ...
 * analyzer.assertNoMemoryLeak(512 * 1024); // 512KB tolerance for fragmentation
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class MemorySnapshotAnalyzer implements AutoCloseable {

  private final RedisCommandExecutor executor;
  private final AtomicReference<MemorySnapshot> snapshotRef = new AtomicReference<>(null);

  /**
   * Creates an analyzer backed by the given executor.
   *
   * @param executor command executor — must not be null
   * @throws NullPointerException if executor is null
   */
  public MemorySnapshotAnalyzer(final RedisCommandExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  // ==================== Factory Methods ====================

  /**
   * Creates a Lettuce-backed analyzer by connecting to the container's mapped Redis port.
   *
   * <p>This is the default and preferred backend. The analyzer owns the connection — call
   * {@link #close()} when done, or use try-with-resources.
   *
   * @param container running Redis container — must not be null
   * @return Lettuce-backed analyzer (owns its connection)
   */
  public static MemorySnapshotAnalyzer forContainer(final GenericContainer<?> container) {
    return forContainer(container, ShellRedisCommandExecutor.DEFAULT_REDIS_PORT);
  }

  /**
   * Creates a Lettuce-backed analyzer connecting to a custom Redis port on the container.
   *
   * @param container running Redis container — must not be null
   * @param port      Redis port inside the container (mapped port is resolved automatically)
   * @return Lettuce-backed analyzer (owns its connection)
   */
  public static MemorySnapshotAnalyzer forContainer(
      final GenericContainer<?> container, final int port) {
    Objects.requireNonNull(container, "container");
    final int mappedPort = container.getMappedPort(port);
    return new MemorySnapshotAnalyzer(
        new LettuceRedisCommandExecutor(container.getHost(), mappedPort));
  }

  /**
   * Creates a Lettuce-backed analyzer using an existing connection.
   *
   * <p>The caller retains ownership of the connection — {@link #close()} is a no-op.
   *
   * @param redisCommands Lettuce sync commands — must not be null
   * @return Lettuce-backed analyzer (does NOT own the connection)
   */
  public static MemorySnapshotAnalyzer forCommands(
      final RedisCommands<String, String> redisCommands) {
    return new MemorySnapshotAnalyzer(new LettuceRedisCommandExecutor(redisCommands));
  }

  /**
   * Creates a shell-backed analyzer for environments where Lettuce is unavailable.
   *
   * @param container running Redis container — must not be null
   * @return shell-backed analyzer
   */
  public static MemorySnapshotAnalyzer forContainerShell(final GenericContainer<?> container) {
    return new MemorySnapshotAnalyzer(new ShellRedisCommandExecutor(container));
  }

  /**
   * Creates a shell-backed analyzer targeting a custom Redis port.
   *
   * @param container running Redis container — must not be null
   * @param port      Redis port inside the container
   * @return shell-backed analyzer
   */
  public static MemorySnapshotAnalyzer forContainerShell(
      final GenericContainer<?> container, final int port) {
    return new MemorySnapshotAnalyzer(new ShellRedisCommandExecutor(container, port));
  }

  /**
   * Closes the underlying executor, releasing any owned Lettuce connection.
   */
  @Override
  public void close() {
    try {
      executor.close();
    } catch (final Exception e) {
      log.debug("Error closing executor", e);
    }
  }

  // ==================== API ====================

  /**
   * Takes a memory snapshot and stores it as the baseline.
   *
   * <p>Calling this multiple times overwrites the previous snapshot.
   */
  public void snapshot() {
    snapshotRef.set(captureSnapshot());
  }

  /**
   * Returns the stored baseline snapshot.
   *
   * @return stored memory snapshot
   * @throws IllegalStateException if no snapshot was taken
   */
  public MemorySnapshot getSnapshot() {
    final MemorySnapshot snapshot = snapshotRef.get();
    if (snapshot == null) {
      throw new IllegalStateException("No snapshot taken — call snapshot() first");
    }
    return snapshot;
  }

  /**
   * Returns a fresh memory snapshot from current Redis state.
   *
   * @return current memory snapshot (never null)
   */
  public MemorySnapshot getCurrent() {
    return captureSnapshot();
  }

  /**
   * Calculates memory delta between current state and stored baseline.
   *
   * @return memory delta in bytes (positive = growth, negative = reduction)
   * @throws IllegalStateException if no snapshot was taken
   */
  public long getMemoryDelta() {
    final MemorySnapshot snapshot = snapshotRef.get();
    if (snapshot == null) {
      throw new IllegalStateException("No snapshot taken — call snapshot() first");
    }
    return getCurrent().deltaFrom(snapshot);
  }

  /**
   * Asserts that memory growth does not exceed the given tolerance.
   *
   * @param toleranceBytes maximum acceptable memory growth in bytes (0 = strict equality)
   * @throws AssertionError        if memory delta exceeds tolerance
   * @throws IllegalStateException if no snapshot was taken
   */
  public void assertNoMemoryLeak(final long toleranceBytes) {
    final long delta = getMemoryDelta();
    if (delta > toleranceBytes) {
      throw new AssertionError(
          String.format(
              "Expected memory delta ≤ %d bytes but was %d bytes (exceeded by %d bytes)",
              toleranceBytes, delta, delta - toleranceBytes));
    }
  }

  // ==================== Internal ====================

  private MemorySnapshot captureSnapshot() {
    final String infoMemory = executor.execute("INFO memory");
    return parseInfoMemory(infoMemory);
  }

  /**
   * Parses {@code INFO memory} output into a {@link MemorySnapshot}.
   *
   * @param infoMemory INFO memory response string
   * @return parsed snapshot (never null; zero values used for missing fields)
   */
  private static MemorySnapshot parseInfoMemory(final String infoMemory) {
    long usedMemory = 0L;
    long usedMemoryPeak = 0L;
    double fragmentationRatio = 0.0;

    if (infoMemory != null && !infoMemory.isBlank()) {
      for (final String line : infoMemory.split("\n")) {
        final String trimmed = line.trim();
        if (trimmed.startsWith("used_memory:")) {
          usedMemory = parseLong(trimmed);
        } else if (trimmed.startsWith("used_memory_peak:")) {
          usedMemoryPeak = parseLong(trimmed);
        } else if (trimmed.startsWith("mem_fragmentation_ratio:")) {
          fragmentationRatio = parseDouble(trimmed);
        }
      }
    }

    return new MemorySnapshot(usedMemory, usedMemoryPeak, fragmentationRatio, Instant.now());
  }

  private static long parseLong(final String line) {
    final int colon = line.indexOf(':');
    if (colon < 0 || colon == line.length() - 1) {
      return 0L;
    }
    try {
      return Long.parseLong(line.substring(colon + 1).trim());
    } catch (final NumberFormatException e) {
      return 0L;
    }
  }

  private static double parseDouble(final String line) {
    final int colon = line.indexOf(':');
    if (colon < 0 || colon == line.length() - 1) {
      return 0.0;
    }
    try {
      return Double.parseDouble(line.substring(colon + 1).trim());
    } catch (final NumberFormatException e) {
      return 0.0;
    }
  }
}
