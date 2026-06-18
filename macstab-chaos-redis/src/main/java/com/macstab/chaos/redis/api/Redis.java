/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.api;

import java.util.List;

import com.macstab.chaos.core.api.ChaosContainers;

/**
 * Base interface for all Redis deployment topologies.
 *
 * <p><strong>Purpose:</strong> Unified contract for standalone, sentinel, and cluster Redis
 * deployments. Enables generic algorithms that work across all topologies while preserving
 * type-specific features.
 *
 * <p><strong>Deployment Topologies:</strong>
 *
 * <ul>
 *   <li>{@link StandaloneRedis} - Single Redis instance
 *   <li>{@link SentinelRedis} - Master + replicas + sentinels (high availability)
 *   <li>{@code ClusterRedis} - Sharded cluster (future)
 * </ul>
 *
 * <p><strong>Unified Access (for generic algorithms):</strong>
 *
 * <pre>{@code
 * // Get all Redis instances regardless of topology
 * List<Redis> all = Redis.getAll();
 *
 * // Connect to any topology
 * all.forEach(redis -> {
 *   Jedis jedis = connect(redis);  // Works for standalone, sentinel, cluster
 * });
 * }</pre>
 *
 * <p><strong>Type-Specific Access (when you know the topology):</strong>
 *
 * <pre>{@code
 * // Type-safe access via annotation INSTANCE
 * StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
 * SentinelRedis session = RedisSentinel.INSTANCE.get("session");
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 * @see StandaloneRedis
 * @see SentinelRedis
 */
public sealed interface Redis permits StandaloneRedis, SentinelRedis {

  /**
   * Gets all Redis instances regardless of topology.
   *
   * <p><strong>Use Case:</strong> Generic algorithms that work with any Redis deployment.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * List<Redis> all = Redis.getAll();
   * all.forEach(redis -> warmup(redis));
   * }</pre>
   *
   * @return list of all Redis instances (empty if none)
   */
  static List<Redis> getAll() {
    return ChaosContainers.getAllByBaseType(Redis.class);
  }

  /**
   * Gets Redis instance by id, searching all topologies.
   *
   * <p><strong>Use Case:</strong> Unified access when you don't know the topology.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * Redis redis = Redis.get("cache");  // Could be standalone or sentinel
   * String info = switch (redis) {
   *   case StandaloneRedis s -> "Standalone: " + s.host();
   *   case SentinelRedis s -> "Sentinel: " + s.masterName();
   * };
   * }</pre>
   *
   * @param id container id
   * @return Redis instance
   * @throws java.util.NoSuchElementException if not found
   */
  static Redis get(final String id) {
    return ChaosContainers.getByBaseType(Redis.class, id);
  }

  /**
   * Primary connection host.
   *
   * <p><strong>Standalone:</strong> The single Redis instance host.
   *
   * <p><strong>Sentinel:</strong> The current master host (may change on failover).
   *
   * @return host address (IP or hostname)
   */
  String getHost();

  /**
   * Primary connection port.
   *
   * <p><strong>Standalone:</strong> The single Redis instance port.
   *
   * <p><strong>Sentinel:</strong> The current master port (may change on failover).
   *
   * @return port number
   */
  int getPort();

  /**
   * Deployment topology type.
   *
   * @return topology (standalone, sentinel, cluster)
   */
  RedisTopology getTopology();
}
