/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.api;

/**
 * Standalone Redis connection info (single instance).
 *
 * <p><strong>Topology:</strong> Single Redis instance with no replication.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * @RedisStandalone(id="cache")
 * class Test {
 *   @Test
 *   void test() {
 *     StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
 *
 *     // Connect with Jedis
 *     Jedis jedis = new Jedis(cache.host(), cache.port());
 *     jedis.set("key", "value");
 *   }
 * }
 * }</pre>
 *
 * @param host Redis host (not null)
 * @param port Redis port (1-65535)
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public record StandaloneRedis(String host, int port) implements Redis {

  public StandaloneRedis {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("Host cannot be null or blank");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
    }
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
    return RedisTopology.STANDALONE;
  }
}
