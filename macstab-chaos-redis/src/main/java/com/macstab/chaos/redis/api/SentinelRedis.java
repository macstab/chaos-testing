/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.api;

import java.util.List;

/**
 * Sentinel Redis connection info (master + replicas + sentinels).
 *
 * <p><strong>Topology:</strong> Master-replica setup with sentinel monitoring for automatic
 * failover (high availability).
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * @RedisSentinel(id="session", replicas=2, sentinels=3)
 * class Test {
 *   @Test
 *   void test() {
 *     SentinelRedis session = RedisSentinel.INSTANCE.get("session");
 *
 *     // Connect via JedisSentinelPool
 *     Set<String> sentinels = session.sentinels().stream()
 *       .map(Endpoint::toString)
 *       .collect(Collectors.toSet());
 *
 *     JedisSentinelPool pool = new JedisSentinelPool(
 *       session.masterName(),
 *       sentinels
 *     );
 *
 *     try (Jedis jedis = pool.getResource()) {
 *       jedis.set("key", "value");
 *     }
 *   }
 * }
 * }</pre>
 *
 * @param host current master host (may change on failover)
 * @param port current master port (may change on failover)
 * @param masterName sentinel master name (e.g., "mymaster")
 * @param sentinels sentinel endpoints (for discovery, immutable)
 * @param replicas replica endpoints (read-only slaves, immutable)
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public record SentinelRedis(
    String host, int port, String masterName, List<Endpoint> sentinels, List<Endpoint> replicas)
    implements Redis {

  public SentinelRedis {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("Host cannot be null or blank");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
    }
    if (masterName == null || masterName.isBlank()) {
      throw new IllegalArgumentException("Master name cannot be null or blank");
    }
    if (sentinels == null || sentinels.isEmpty()) {
      throw new IllegalArgumentException("Sentinels list cannot be null or empty");
    }
    if (replicas == null) {
      throw new IllegalArgumentException("Replicas list cannot be null");
    }

    // Defensive copy (immutability)
    sentinels = List.copyOf(sentinels);
    replicas = List.copyOf(replicas);
  }

  @Override
  public String getHost() {
    return host;
  }

  @Override
  public int getPort() {
    return port;
  }

  @Override
  public RedisTopology getTopology() {
    return RedisTopology.SENTINEL;
  }
}
