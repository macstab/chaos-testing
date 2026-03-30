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
 * (or timeout), and reports a {@link ConsistencyResult} with matching/missing counts.
 *
 * <p><strong>Two backends, one API:</strong>
 * <ul>
 *   <li>{@link #forContainers(GenericContainer, GenericContainer)} — shell-backed via
 *       {@code redis-cli} inside each container. No Lettuce required. Suitable for up to ~200 keys
 *       per test (one shell exec per key).
 *   <li>{@link #forCommands(RedisCommands, RedisCommands)} — Lettuce-backed. Preferred for large
 *       key counts or when connections are already available. Zero process-spawn overhead.
 * </ul>
 *
 * <p><strong>Port configuration:</strong> Factory methods default to port 6379. Use
 * {@link #forContainers(GenericContainer, int, GenericContainer, int)} for non-standard ports.
 *
 * <p><strong>Example (container-backed):</strong>
 * <pre>{@code
 * ReplicationConsistencyVerifier verifier =
 *     ReplicationConsistencyVerifier.forContainers(masterContainer, replicaContainer);
 * ConsistencyResult result = verifier.verify(50);
 * result.assertFullConsistency();
 * }</pre>
 *
 * <p><strong>Example (Lettuce-backed, high-volume):</strong>
 * <pre>{@code
 * ReplicationConsistencyVerifier verifier =
 *     ReplicationConsistencyVerifier.forCommands(masterCommands, replicaCommands);
 * ConsistencyResult result = verifier.verify(1000, Duration.ofSeconds(10));
 * result.assertConsistencyAtLeast(0.99);
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

  // Exactly one of these two backends is non-null
  private final RedisCommandExecutor masterExecutor;
  private final RedisCommandExecutor replicaExecutor;
  private final RedisCommands<String, String> masterLettuceCommands;
  private final RedisCommands<String, String> replicaLettuceCommands;

  /**
   * Creates a shell-backed verifier. Use factory methods for convenience.
   *
   * @param masterExecutor  executor for master container — must not be null
   * @param replicaExecutor executor for replica container — must not be null
   */
  public ReplicationConsistencyVerifier(
      final RedisCommandExecutor masterExecutor,
      final RedisCommandExecutor replicaExecutor) {
    this.masterExecutor = Objects.requireNonNull(masterExecutor, "masterExecutor");
    this.replicaExecutor = Objects.requireNonNull(replicaExecutor, "replicaExecutor");
    this.masterLettuceCommands = null;
    this.replicaLettuceCommands = null;
  }

  /**
   * Creates a Lettuce-backed verifier. Use factory methods for convenience.
   *
   * @param masterCommands  Lettuce commands for master — must not be null
   * @param replicaCommands Lettuce commands for replica — must not be null
   */
  public ReplicationConsistencyVerifier(
      final RedisCommands<String, String> masterCommands,
      final RedisCommands<String, String> replicaCommands) {
    this.masterLettuceCommands = Objects.requireNonNull(masterCommands, "masterCommands");
    this.replicaLettuceCommands = Objects.requireNonNull(replicaCommands, "replicaCommands");
    this.masterExecutor = null;
    this.replicaExecutor = null;
  }

  // ==================== Factory Methods ====================

  /**
   * Creates a Lettuce-backed verifier by connecting to both containers' mapped Redis ports.
   *
   * <p>This is the default and preferred backend. Zero per-key process overhead — all writes and
   * reads use the existing Lettuce connection. The verifier owns both connections — call
   * {@link #close()} when done, or use try-with-resources.
   *
   * @param master  running master container — must not be null
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
   * @param master      running master container — must not be null
   * @param masterPort  Redis port inside the master container
   * @param replica     running replica container — must not be null
   * @param replicaPort Redis port inside the replica container
   * @return Lettuce-backed verifier (owns its connections)
   */
  public static ReplicationConsistencyVerifier forContainers(
      final GenericContainer<?> master, final int masterPort,
      final GenericContainer<?> replica, final int replicaPort) {
    Objects.requireNonNull(master, "master");
    Objects.requireNonNull(replica, "replica");
    return new ReplicationConsistencyVerifier(
        new LettuceRedisCommandExecutor(master.getHost(), master.getMappedPort(masterPort)),
        new LettuceRedisCommandExecutor(replica.getHost(), replica.getMappedPort(replicaPort)));
  }

  /**
   * Creates a Lettuce-backed verifier using existing connections.
   *
   * <p>The caller retains ownership — {@link #close()} is a no-op.
   *
   * @param masterCommands  Lettuce sync commands for master — must not be null
   * @param replicaCommands Lettuce sync commands for replica — must not be null
   * @return Lettuce-backed verifier (does NOT own the connections)
   */
  public static ReplicationConsistencyVerifier forCommands(
      final RedisCommands<String, String> masterCommands,
      final RedisCommands<String, String> replicaCommands) {
    return new ReplicationConsistencyVerifier(masterCommands, replicaCommands);
  }

  /**
   * Creates a shell-backed verifier for environments where Lettuce is unavailable.
   *
   * <p>Uses one {@code redis-cli} process per key. Suitable for up to ~200 keys per test.
   *
   * @param master  running master container — must not be null
   * @param replica running replica container — must not be null
   * @return shell-backed verifier
   */
  public static ReplicationConsistencyVerifier forContainersShell(
      final GenericContainer<?> master, final GenericContainer<?> replica) {
    return new ReplicationConsistencyVerifier(
        new ShellRedisCommandExecutor(master),
        new ShellRedisCommandExecutor(replica));
  }

  /**
   * Creates a shell-backed verifier with explicit ports.
   *
   * @param master      running master container — must not be null
   * @param masterPort  Redis port inside the master container
   * @param replica     running replica container — must not be null
   * @param replicaPort Redis port inside the replica container
   * @return shell-backed verifier
   */
  public static ReplicationConsistencyVerifier forContainersShell(
      final GenericContainer<?> master, final int masterPort,
      final GenericContainer<?> replica, final int replicaPort) {
    return new ReplicationConsistencyVerifier(
        new ShellRedisCommandExecutor(master, masterPort),
        new ShellRedisCommandExecutor(replica, replicaPort));
  }

  /**
   * Closes owned Lettuce connections (master and replica), if any.
   */
  @Override
  public void close() {
    if (masterExecutor != null) {
      try { masterExecutor.close(); } catch (final Exception e) { log.debug("Error closing master executor", e); }
    }
    if (replicaExecutor != null) {
      try { replicaExecutor.close(); } catch (final Exception e) { log.debug("Error closing replica executor", e); }
    }
  }

  // ==================== API ====================

  /**
   * Verifies replication consistency using the default timeout of 5 seconds.
   *
   * @param keyCount number of unique test keys to write — must be &gt; 0
   * @return consistency result with matching/missing counts
   */
  public ConsistencyResult verify(final int keyCount) {
    return verify(keyCount, DEFAULT_TIMEOUT);
  }

  /**
   * Verifies replication consistency with a custom timeout.
   *
   * @param keyCount number of unique test keys to write — must be &gt; 0
   * @param timeout  maximum wait for all keys to replicate — must not be null
   * @return consistency result with matching/missing counts
   */
  public ConsistencyResult verify(final int keyCount, final Duration timeout) {
    if (keyCount <= 0) {
      throw new IllegalArgumentException("keyCount must be > 0 but was: " + keyCount);
    }
    Objects.requireNonNull(timeout, "timeout");

    // Lettuce direct path: typed commands, zero per-call overhead
    if (masterLettuceCommands != null) {
      return verifyWithLettuceCommands(keyCount, timeout);
    }
    // Executor path: handles both LettuceRedisCommandExecutor and ShellRedisCommandExecutor
    return verifyWithExecutor(keyCount, timeout);
  }

  // ==================== Lettuce direct backend (forCommands) ====================

  private ConsistencyResult verifyWithLettuceCommands(final int keyCount, final Duration timeout) {
    final List<String> keys = generateKeys(keyCount);
    try {
      keys.forEach(key -> masterLettuceCommands.set(key, key));
      return pollForConsistency(keyCount, timeout, keys,
          key -> replicaLettuceCommands.get(key));
    } finally {
      keys.forEach(key -> {
        try { masterLettuceCommands.del(key); } catch (final Exception e) { /* best-effort */ }
      });
    }
  }

  // ==================== Executor backend (forContainers / forContainersShell) ====================

  private ConsistencyResult verifyWithExecutor(final int keyCount, final Duration timeout) {
    final List<String> keys = generateKeys(keyCount);
    try {
      keys.forEach(key -> masterExecutor.execute("SET " + key + " " + key));
      return pollForConsistency(keyCount, timeout, keys, key -> {
        final String result = replicaExecutor.execute("GET " + key);
        // redis-cli returns "(nil)" for missing keys; Lettuce executor returns empty string
        final String trimmed = result == null ? "" : result.trim();
        return "(nil)".equals(trimmed) || trimmed.isEmpty() ? null : trimmed;
      });
    } finally {
      keys.forEach(key -> {
        try { masterExecutor.execute("DEL " + key); } catch (final Exception e) { /* best-effort */ }
      });
    }
  }

  // ==================== Shared polling logic ====================

  @FunctionalInterface
  private interface KeyReader {
    String get(String key);
  }

  private ConsistencyResult pollForConsistency(
      final int keyCount, final Duration timeout,
      final List<String> keys, final KeyReader reader) {

    final long deadlineMs = System.currentTimeMillis() + timeout.toMillis();
    int matchingKeys = 0;

    while (System.currentTimeMillis() < deadlineMs) {
      matchingKeys = 0;
      for (final String key : keys) {
        final String value = reader.get(key);
        if (key.equals(value)) {
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
}
