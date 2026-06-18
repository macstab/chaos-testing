/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.util.inspector.executor.LettuceRedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.executor.RedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.executor.ShellRedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.model.ClientConnectionInfo;

import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

/**
 * Tracks Redis client connection leaks by comparing CLIENT LIST snapshots.
 *
 * <p>Take a snapshot before a test, then verify no new connections remain after the test completes.
 * Useful for detecting connection pool leaks in integration tests.
 *
 * <p><strong>Backend:</strong> Works with any container via {@link #forContainer(GenericContainer)}
 * (shell-backed, no Lettuce required), or with an existing Lettuce connection via {@link
 * #forCommands(RedisCommands)}.
 *
 * <p><strong>Lifecycle — one instance per test method:</strong>
 *
 * <pre>
 *   &#64;BeforeEach
 *   void setUp() {
 *       tracker = ConnectionLeakTracker.forContainer(redisContainer);
 *   }
 * </pre>
 *
 * Do not share instances across test methods. {@link #snapshot()} resets state — calling it
 * mid-test discards the previous baseline.
 *
 * <p><strong>Thread safety:</strong> The snapshot reference is held in an {@link AtomicReference}.
 * Designed for single-threaded test execution only — concurrent {@link #snapshot()} calls will
 * race.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * ConnectionLeakTracker tracker = ConnectionLeakTracker.forContainer(redisContainer);
 * tracker.snapshot();
 * // ... perform operations that should not leak connections ...
 * tracker.assertNoLeaks();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class ConnectionLeakTracker implements AutoCloseable {

  private final RedisCommandExecutor executor;
  private final AtomicReference<Map<Long, ClientConnectionInfo>> snapshotRef =
      new AtomicReference<>(null);

  /**
   * Creates a tracker backed by the given executor.
   *
   * @param executor command executor — must not be null
   * @throws NullPointerException if executor is null
   */
  public ConnectionLeakTracker(final RedisCommandExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  // ==================== Factory Methods ====================

  /**
   * Creates a Lettuce-backed tracker by connecting to the container's mapped Redis port.
   *
   * <p>This is the default and preferred backend. Lettuce provides connection reuse, efficient I/O,
   * and zero per-call process overhead. The tracker owns the connection — call {@link #close()}
   * when done, or use try-with-resources.
   *
   * @param container running Redis container — must not be null
   * @return Lettuce-backed tracker (owns its connection)
   */
  public static ConnectionLeakTracker forContainer(final GenericContainer<?> container) {
    return forContainer(container, ShellRedisCommandExecutor.DEFAULT_REDIS_PORT);
  }

  /**
   * Creates a Lettuce-backed tracker connecting to a custom Redis port on the container.
   *
   * @param container running Redis container — must not be null
   * @param port Redis port inside the container (mapped port is resolved automatically)
   * @return Lettuce-backed tracker (owns its connection)
   */
  public static ConnectionLeakTracker forContainer(
      final GenericContainer<?> container, final int port) {
    Objects.requireNonNull(container, "container");
    final int mappedPort = container.getMappedPort(port);
    return new ConnectionLeakTracker(
        new LettuceRedisCommandExecutor(container.getHost(), mappedPort));
  }

  /**
   * Creates a Lettuce-backed tracker using an existing connection.
   *
   * <p>The caller retains ownership of the connection — {@link #close()} is a no-op.
   *
   * @param redisCommands Lettuce sync commands — must not be null
   * @return Lettuce-backed tracker (does NOT own the connection)
   */
  public static ConnectionLeakTracker forCommands(
      final RedisCommands<String, String> redisCommands) {
    return new ConnectionLeakTracker(new LettuceRedisCommandExecutor(redisCommands));
  }

  /**
   * Creates a shell-backed tracker for environments where Lettuce is unavailable.
   *
   * <p>Uses {@code redis-cli} inside the container. No connection management required. Useful for
   * DinD, network-isolated, or Podman container topologies.
   *
   * @param container running Redis container — must not be null
   * @return shell-backed tracker
   */
  public static ConnectionLeakTracker forContainerShell(final GenericContainer<?> container) {
    return new ConnectionLeakTracker(new ShellRedisCommandExecutor(container));
  }

  /**
   * Creates a shell-backed tracker targeting a custom Redis port.
   *
   * @param container running Redis container — must not be null
   * @param port Redis port inside the container
   * @return shell-backed tracker
   */
  public static ConnectionLeakTracker forContainerShell(
      final GenericContainer<?> container, final int port) {
    return new ConnectionLeakTracker(new ShellRedisCommandExecutor(container, port));
  }

  /**
   * Closes the underlying executor, releasing any owned Lettuce connection.
   *
   * <p>No-op if the executor was constructed with an externally-managed connection.
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
   * Takes a snapshot of current client connections.
   *
   * <p>Calling this multiple times resets the snapshot to the current state.
   */
  public void snapshot() {
    snapshotRef.set(getCurrentConnections());
  }

  /**
   * Returns new connections that appeared since the snapshot.
   *
   * @return list of new connections (never null, may be empty)
   * @throws IllegalStateException if no snapshot was taken
   */
  public List<ClientConnectionInfo> getNewConnections() {
    final Map<Long, ClientConnectionInfo> snapshot = snapshotRef.get();
    if (snapshot == null) {
      throw new IllegalStateException("No snapshot taken — call snapshot() first");
    }

    final Map<Long, ClientConnectionInfo> current = getCurrentConnections();
    final List<ClientConnectionInfo> newConnections = new ArrayList<>();

    for (final Map.Entry<Long, ClientConnectionInfo> entry : current.entrySet()) {
      if (!snapshot.containsKey(entry.getKey())) {
        newConnections.add(entry.getValue());
      }
    }

    return newConnections;
  }

  /**
   * Asserts that no connection leaks occurred since the snapshot.
   *
   * @throws AssertionError if new connections are present
   * @throws IllegalStateException if no snapshot was taken
   */
  public void assertNoLeaks() {
    final List<ClientConnectionInfo> newConnections = getNewConnections();
    if (!newConnections.isEmpty()) {
      throw new AssertionError(
          String.format(
              "Expected no connection leaks but found %d new connection(s)",
              newConnections.size()));
    }
  }

  /**
   * Checks if a snapshot has been taken.
   *
   * @return true if {@link #snapshot()} was called at least once
   */
  public boolean hasSnapshot() {
    return snapshotRef.get() != null;
  }

  // ==================== Internal ====================

  private Map<Long, ClientConnectionInfo> getCurrentConnections() {
    final String clientList = executor.execute("CLIENT LIST");
    return parseClientList(clientList);
  }

  /**
   * Parses a CLIENT LIST response into a map of connection ID to {@link ClientConnectionInfo}.
   *
   * @param clientList CLIENT LIST response string
   * @return map of connection ID to info (never null, may be empty)
   */
  private static Map<Long, ClientConnectionInfo> parseClientList(final String clientList) {
    final Map<Long, ClientConnectionInfo> connections = new HashMap<>();

    if (clientList == null || clientList.isBlank()) {
      return connections;
    }

    for (final String line : clientList.split("\n")) {
      final ClientConnectionInfo info = parseClientListLine(line.trim());
      if (info != null) {
        connections.put(info.id(), info);
      }
    }

    return connections;
  }

  /**
   * Parses a single CLIENT LIST line.
   *
   * <p>Format: {@code id=N addr=host:port ... name=NAME age=N idle=N ... cmd=LAST_CMD db=N ...}
   * Fields may appear in any order; missing fields default to 0 or empty string.
   *
   * @param line single CLIENT LIST line
   * @return parsed info, or null if line is empty or malformed
   */
  private static ClientConnectionInfo parseClientListLine(final String line) {
    if (line == null || line.isBlank()) {
      return null;
    }

    final Map<String, String> fields = new HashMap<>();
    for (final String token : line.split(" ")) {
      final int eqPos = token.indexOf('=');
      if (eqPos > 0) {
        fields.put(token.substring(0, eqPos), token.substring(eqPos + 1));
      }
    }

    try {
      final long id = Long.parseLong(fields.getOrDefault("id", "0"));
      final String addr = fields.getOrDefault("addr", "");
      final String name = fields.getOrDefault("name", "");
      final long ageSeconds = Long.parseLong(fields.getOrDefault("age", "0"));
      final long idleSeconds = Long.parseLong(fields.getOrDefault("idle", "0"));
      final String lastCmd = fields.getOrDefault("cmd", "");
      final int db = Integer.parseInt(fields.getOrDefault("db", "0"));

      return new ClientConnectionInfo(id, addr, name, ageSeconds, idleSeconds, lastCmd, db);
    } catch (final NumberFormatException e) {
      return null;
    }
  }
}
