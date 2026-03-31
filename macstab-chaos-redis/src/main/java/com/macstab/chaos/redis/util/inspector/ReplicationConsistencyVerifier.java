/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.util.inspector.executor.LettuceRedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.executor.RedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.executor.ShellRedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.model.ConsistencyResult;

import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

/**
 * Verifies replication consistency between a Redis master and replica.
 *
 * <p>Writes {@code keyCount} unique test keys to the master, polls the replica until all replicate
 * (or timeout), and reports a {@link ConsistencyResult} with matching/missing counts and assertion
 * helpers.
 *
 * <p><strong>Single strategy — {@link RedisCommandExecutor}:</strong> All backends go through the
 * executor abstraction. {@link #forContainers} creates Lettuce-backed executors (default, zero
 * process overhead). {@link #forContainersShell} uses shell executors for DinD/Podman topologies.
 * {@link #forCommands} wraps existing Lettuce connections. One code path, no nullable fields.
 *
 * <p><strong>Port configuration:</strong> Factory methods default to port 6379. Use the
 * explicit-port overloads for non-standard deployments.
 *
 * <p><strong>Lifecycle:</strong> Implements {@link AutoCloseable}. Use try-with-resources when
 * created via {@link #forContainers} (owns connections). {@link #forCommands} is a no-op on close.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * try (ReplicationConsistencyVerifier verifier =
 *         ReplicationConsistencyVerifier.forContainers(master, replica)) {
 *     verifier.verify(50).assertFullConsistency();
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class ReplicationConsistencyVerifier implements AutoCloseable {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(10);
  private static final String KEY_PREFIX = "consistency-check-";

  private final RedisCommandExecutor masterExecutor;
  private final RedisCommandExecutor replicaExecutor;

  /**
   * Creates a verifier backed by the given executors. Use factory methods for convenience.
   *
   * @param masterExecutor executor for the master — must not be null
   * @param replicaExecutor executor for the replica — must not be null
   */
  public ReplicationConsistencyVerifier(
      final RedisCommandExecutor masterExecutor, final RedisCommandExecutor replicaExecutor) {
    this.masterExecutor = Objects.requireNonNull(masterExecutor, "masterExecutor");
    this.replicaExecutor = Objects.requireNonNull(replicaExecutor, "replicaExecutor");
  }

  // ==================== Factory Methods ====================

  /**
   * Creates a Lettuce-backed verifier connecting to both containers on the default port (6379).
   *
   * <p>Preferred backend — zero per-key process overhead. Owns both connections; call {@link
   * #close()} when done or use try-with-resources.
   *
   * @param master running master container — must not be null
   * @param replica running replica container — must not be null
   * @return Lettuce-backed verifier (owns its connections)
   */
  public static ReplicationConsistencyVerifier forContainers(
      final GenericContainer<?> master, final GenericContainer<?> replica) {
    return forContainers(
        master, ShellRedisCommandExecutor.DEFAULT_REDIS_PORT,
        replica, ShellRedisCommandExecutor.DEFAULT_REDIS_PORT);
  }

  /**
   * Creates a Lettuce-backed verifier with explicit ports on each container.
   *
   * @param master running master container — must not be null
   * @param masterPort Redis port inside the master container
   * @param replica running replica container — must not be null
   * @param replicaPort Redis port inside the replica container
   * @return Lettuce-backed verifier (owns its connections)
   */
  public static ReplicationConsistencyVerifier forContainers(
      final GenericContainer<?> master,
      final int masterPort,
      final GenericContainer<?> replica,
      final int replicaPort) {
    Objects.requireNonNull(master, "master");
    Objects.requireNonNull(replica, "replica");
    return new ReplicationConsistencyVerifier(
        new LettuceRedisCommandExecutor(master.getHost(), master.getMappedPort(masterPort)),
        new LettuceRedisCommandExecutor(replica.getHost(), replica.getMappedPort(replicaPort)));
  }

  /**
   * Creates a Lettuce-backed verifier wrapping existing connections.
   *
   * <p>The caller retains ownership of the connections — {@link #close()} is a no-op.
   *
   * @param masterCommands Lettuce sync commands for master — must not be null
   * @param replicaCommands Lettuce sync commands for replica — must not be null
   * @return verifier backed by the given connections (does NOT own them)
   */
  public static ReplicationConsistencyVerifier forCommands(
      final RedisCommands<String, String> masterCommands,
      final RedisCommands<String, String> replicaCommands) {
    return new ReplicationConsistencyVerifier(
        new LettuceRedisCommandExecutor(masterCommands),
        new LettuceRedisCommandExecutor(replicaCommands));
  }

  /**
   * Creates a shell-backed verifier for DinD, network-isolated, or Podman topologies.
   *
   * <p>Uses one {@code redis-cli} process per key — suitable for up to ~200 keys per test.
   *
   * @param master running master container — must not be null
   * @param replica running replica container — must not be null
   * @return shell-backed verifier
   */
  public static ReplicationConsistencyVerifier forContainersShell(
      final GenericContainer<?> master, final GenericContainer<?> replica) {
    return new ReplicationConsistencyVerifier(
        new ShellRedisCommandExecutor(master), new ShellRedisCommandExecutor(replica));
  }

  /**
   * Creates a shell-backed verifier with explicit ports.
   *
   * @param master running master container — must not be null
   * @param masterPort Redis port inside the master container
   * @param replica running replica container — must not be null
   * @param replicaPort Redis port inside the replica container
   * @return shell-backed verifier
   */
  public static ReplicationConsistencyVerifier forContainersShell(
      final GenericContainer<?> master,
      final int masterPort,
      final GenericContainer<?> replica,
      final int replicaPort) {
    return new ReplicationConsistencyVerifier(
        new ShellRedisCommandExecutor(master, masterPort),
        new ShellRedisCommandExecutor(replica, replicaPort));
  }

  /** Closes owned executors, releasing any Lettuce connections created by this verifier. */
  @Override
  public void close() {
    closeQuietly(masterExecutor, "master");
    closeQuietly(replicaExecutor, "replica");
  }

  // ==================== API ====================

  /**
   * Verifies replication consistency with a 5-second timeout.
   *
   * @param keyCount number of unique test keys — must be &gt; 0
   * @return consistency result
   */
  public ConsistencyResult verify(final int keyCount) {
    return verify(keyCount, DEFAULT_TIMEOUT);
  }

  /**
   * Verifies replication consistency with a custom timeout.
   *
   * @param keyCount number of unique test keys — must be &gt; 0
   * @param timeout maximum wait for replication — must not be null
   * @return consistency result
   */
  public ConsistencyResult verify(final int keyCount, final Duration timeout) {
    if (keyCount <= 0) {
      throw new IllegalArgumentException("keyCount must be > 0 but was: " + keyCount);
    }
    Objects.requireNonNull(timeout, "timeout");

    final List<String> keys = generateKeys(keyCount);
    try {
      keys.forEach(key -> masterExecutor.execute("SET " + key + " " + key));
      return pollForConsistency(keyCount, timeout, keys);
    } finally {
      keys.forEach(
          key -> {
            try {
              masterExecutor.execute("DEL " + key);
            } catch (final Exception e) {
              log.debug("Cleanup failed for key {}", key, e);
            }
          });
    }
  }

  // ==================== Internal ====================

  private ConsistencyResult pollForConsistency(
      final int keyCount, final Duration timeout, final List<String> keys) {

    final long deadlineMs = System.currentTimeMillis() + timeout.toMillis();
    int matchingKeys = 0;

    while (System.currentTimeMillis() < deadlineMs) {
      matchingKeys = 0;
      for (final String key : keys) {
        if (key.equals(readFromReplica(key))) {
          matchingKeys++;
        }
      }
      if (matchingKeys == keyCount) {
        break;
      }
      sleep();
    }

    return new ConsistencyResult(keyCount, matchingKeys, keyCount - matchingKeys);
  }

  private String readFromReplica(final String key) {
    final String result = replicaExecutor.execute("GET " + key);
    final String trimmed = result == null ? "" : result.trim();
    // redis-cli returns "(nil)" for missing keys; LettuceRedisCommandExecutor returns ""
    return "(nil)".equals(trimmed) || trimmed.isEmpty() ? null : trimmed;
  }

  private static List<String> generateKeys(final int count) {
    final List<String> keys = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      keys.add(KEY_PREFIX + UUID.randomUUID());
    }
    return keys;
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void closeQuietly(final RedisCommandExecutor executor, final String name) {
    try {
      executor.close();
    } catch (final Exception e) {
      log.debug("Error closing {} executor", name, e);
    }
  }
}
