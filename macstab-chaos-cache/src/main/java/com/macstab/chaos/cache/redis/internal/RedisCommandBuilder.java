/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.redis.internal;

/**
 * Builds redis-cli shell commands for cache chaos operations.
 *
 * <p>All methods return shell command strings suitable for execution via {@link
 * com.macstab.chaos.core.util.Shell#exec}. Commands always target the real Redis port directly —
 * bypassing the Toxiproxy layer so data-level operations work regardless of active TCP fault
 * injection.
 *
 * <p><strong>INTERNAL USE ONLY</strong> — implementation detail of {@link
 * com.macstab.chaos.cache.redis.RedisCacheChaosProvider}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisCommandBuilder {

  /** Private constructor — utility class. */
  private RedisCommandBuilder() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Build command to evict a percentage of all keys via SCAN + DEL pipeline.
   *
   * <p>Uses a single shell pipeline: scan all keys → take {@code percentage}% → pipe to DEL.
   *
   * @param redisPort Redis listen port inside the container
   * @param percentage percentage of keys to evict (1–100)
   * @return shell command string
   */
  public static String buildForceEvictionCommand(final int redisPort, final int percentage) {
    return String.format(
        "redis-cli -p %d --scan --pattern '*' 2>/dev/null | "
            + "head -n $(redis-cli -p %d DBSIZE 2>/dev/null | awk '{print int($1 * %d / 100)}') | "
            + "xargs -r redis-cli -p %d DEL >/dev/null 2>&1",
        redisPort, redisPort, percentage, redisPort);
  }

  /**
   * Build command to set the Redis memory limit via {@code CONFIG SET maxmemory}.
   *
   * @param redisPort Redis listen port inside the container
   * @param bytes memory limit in bytes (0 = no limit)
   * @return shell command string
   */
  public static String buildSetMemoryLimitCommand(final int redisPort, final long bytes) {
    return String.format("redis-cli -p %d CONFIG SET maxmemory %d", redisPort, bytes);
  }

  /**
   * Build command to set the Redis eviction policy via {@code CONFIG SET maxmemory-policy}.
   *
   * @param redisPort Redis listen port inside the container
   * @param policy eviction policy (e.g., "allkeys-lru", "volatile-lru", "noeviction")
   * @return shell command string
   */
  public static String buildSetEvictionPolicyCommand(final int redisPort, final String policy) {
    return String.format("redis-cli -p %d CONFIG SET maxmemory-policy %s", redisPort, policy);
  }

  /**
   * Build command to kill all connected clients via {@code CLIENT KILL}.
   *
   * <p>Extracts client IDs from {@code CLIENT LIST} and kills each one individually.
   *
   * @param redisPort Redis listen port inside the container
   * @return shell command string
   */
  public static String buildDisconnectClientsCommand(final int redisPort) {
    return String.format(
        "redis-cli -p %d CLIENT LIST 2>/dev/null | "
            + "awk -F'[: ]' '/id=/ {print $2}' | "
            + "xargs -I{} redis-cli -p %d CLIENT KILL ID {} >/dev/null 2>&1 || true",
        redisPort, redisPort);
  }

  /**
   * Build command to flush all keys from all Redis databases via {@code FLUSHALL}.
   *
   * @param redisPort Redis listen port inside the container
   * @return shell command string
   */
  public static String buildFlushAllCommand(final int redisPort) {
    return String.format("redis-cli -p %d FLUSHALL", redisPort);
  }

  /**
   * Build command to check Redis DBSIZE (number of keys in default database).
   *
   * @param redisPort Redis listen port inside the container
   * @return shell command string
   */
  public static String buildDbSizeCommand(final int redisPort) {
    return String.format("redis-cli -p %d DBSIZE", redisPort);
  }
}
