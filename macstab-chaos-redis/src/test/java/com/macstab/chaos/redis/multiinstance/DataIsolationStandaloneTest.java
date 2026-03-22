/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Data isolation test validating that multiple standalone Redis instances are truly independent.
 *
 * <p><strong>Test Strategy:</strong> Write data to one instance, verify it exists there and does
 * NOT exist in other instances.
 *
 * <p><strong>Coverage:</strong>
 *
 * <ul>
 *   <li>✅ 3x standalone instances run independently
 *   <li>✅ Data written to instance A is NOT visible in instances B or C
 *   <li>✅ Each instance can hold different data with same keys
 *   <li>✅ Different Redis versions can coexist (7.4 and 7.2)
 *   <li>✅ Cleanup between tests prevents cross-contamination
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisStandalone(id = "cache", version = "7.4")
@RedisStandalone(id = "session", version = "7.2")
@RedisStandalone(id = "rate-limiter", version = "7.4")
@DisplayName("Data Isolation Test - Multiple Standalone Instances")
class DataIsolationStandaloneTest {

  @AfterEach
  void cleanup(final List<RedisConnectionInfo> instances) {
    // Clean up all data after each test to prevent cross-contamination
    instances.forEach(
        instance -> {
          try (final RedisClient client = createClient(instance);
              final StatefulRedisConnection<String, String> conn = client.connect()) {
            conn.sync().flushall();
          }
        });
  }

  @Test
  @DisplayName("Should isolate data between cache, session, and rate-limiter instances")
  void shouldIsolateDataBetweenInstances() {
    // ARRANGE: Get all instances
    final RedisConnectionInfo cache = RedisStandalone.INSTANCE.get("cache");
    final RedisConnectionInfo session = RedisStandalone.INSTANCE.get("session");
    final RedisConnectionInfo rateLimiter = RedisStandalone.INSTANCE.get("rate-limiter");

    try (final RedisClient cacheClient = createClient(cache);
        final RedisClient sessionClient = createClient(session);
        final RedisClient rateLimiterClient = createClient(rateLimiter);
        final StatefulRedisConnection<String, String> cacheConn = cacheClient.connect();
        final StatefulRedisConnection<String, String> sessionConn = sessionClient.connect();
        final StatefulRedisConnection<String, String> rateLimiterConn =
            rateLimiterClient.connect()) {

      final RedisCommands<String, String> cacheCmd = cacheConn.sync();
      final RedisCommands<String, String> sessionCmd = sessionConn.sync();
      final RedisCommands<String, String> rateLimiterCmd = rateLimiterConn.sync();

      // ACT: Write data to cache instance
      cacheCmd.set("user:123", "cached-user-data");

      // ACT: Write data to session instance
      sessionCmd.set("session:abc", "session-token");

      // ACT: Write data to rate-limiter instance
      rateLimiterCmd.set("limit:user:123", "10");

      // ASSERT: Cache instance has only cache data
      assertThat(cacheCmd.get("user:123")).isEqualTo("cached-user-data");
      assertThat(cacheCmd.get("session:abc")).isNull(); // NOT in cache
      assertThat(cacheCmd.get("limit:user:123")).isNull(); // NOT in cache

      // ASSERT: Session instance has only session data
      assertThat(sessionCmd.get("session:abc")).isEqualTo("session-token");
      assertThat(sessionCmd.get("user:123")).isNull(); // NOT in session
      assertThat(sessionCmd.get("limit:user:123")).isNull(); // NOT in session

      // ASSERT: Rate-limiter instance has only rate-limiter data
      assertThat(rateLimiterCmd.get("limit:user:123")).isEqualTo("10");
      assertThat(rateLimiterCmd.get("user:123")).isNull(); // NOT in rate-limiter
      assertThat(rateLimiterCmd.get("session:abc")).isNull(); // NOT in rate-limiter
    }
  }

  @Test
  @DisplayName("Should allow same keys with different values in each instance")
  void shouldAllowSameKeysWithDifferentValues(final List<RedisConnectionInfo> instances) {
    final RedisConnectionInfo cache = instances.get(0);
    final RedisConnectionInfo session = instances.get(1);
    final RedisConnectionInfo rateLimiter = instances.get(2);

    try (final RedisClient cacheClient = createClient(cache);
        final RedisClient sessionClient = createClient(session);
        final RedisClient rateLimiterClient = createClient(rateLimiter);
        final StatefulRedisConnection<String, String> cacheConn = cacheClient.connect();
        final StatefulRedisConnection<String, String> sessionConn = sessionClient.connect();
        final StatefulRedisConnection<String, String> rateLimiterConn =
            rateLimiterClient.connect()) {

      final RedisCommands<String, String> cacheCmd = cacheConn.sync();
      final RedisCommands<String, String> sessionCmd = sessionConn.sync();
      final RedisCommands<String, String> rateLimiterCmd = rateLimiterConn.sync();

      // ACT: Write same key to all instances with different values
      cacheCmd.set("shared-key", "value-from-cache");
      sessionCmd.set("shared-key", "value-from-session");
      rateLimiterCmd.set("shared-key", "value-from-rate-limiter");

      // ASSERT: Each instance has its own value for the same key
      assertThat(cacheCmd.get("shared-key")).isEqualTo("value-from-cache");
      assertThat(sessionCmd.get("shared-key")).isEqualTo("value-from-session");
      assertThat(rateLimiterCmd.get("shared-key")).isEqualTo("value-from-rate-limiter");

      // ASSERT: All values are different (complete isolation)
      assertThat(cacheCmd.get("shared-key")).isNotEqualTo(sessionCmd.get("shared-key"));
      assertThat(sessionCmd.get("shared-key")).isNotEqualTo(rateLimiterCmd.get("shared-key"));
    }
  }

  @Test
  @DisplayName("Should support different Redis data structures in each instance")
  void shouldSupportDifferentDataStructures() {
    final RedisConnectionInfo cache = RedisStandalone.INSTANCE.get("cache");
    final RedisConnectionInfo session = RedisStandalone.INSTANCE.get("session");
    final RedisConnectionInfo rateLimiter = RedisStandalone.INSTANCE.get("rate-limiter");

    try (final RedisClient cacheClient = createClient(cache);
        final RedisClient sessionClient = createClient(session);
        final RedisClient rateLimiterClient = createClient(rateLimiter);
        final StatefulRedisConnection<String, String> cacheConn = cacheClient.connect();
        final StatefulRedisConnection<String, String> sessionConn = sessionClient.connect();
        final StatefulRedisConnection<String, String> rateLimiterConn =
            rateLimiterClient.connect()) {

      final RedisCommands<String, String> cacheCmd = cacheConn.sync();
      final RedisCommands<String, String> sessionCmd = sessionConn.sync();
      final RedisCommands<String, String> rateLimiterCmd = rateLimiterConn.sync();

      // ACT: Cache uses hashes
      cacheCmd.hset("user:123", "name", "John Doe");
      cacheCmd.hset("user:123", "email", "john@example.com");

      // ACT: Session uses strings with TTL
      sessionCmd.setex("session:abc", 3600, "token-data");

      // ACT: Rate-limiter uses sorted sets
      rateLimiterCmd.zadd("api:requests", 1.0, "request-1");
      rateLimiterCmd.zadd("api:requests", 2.0, "request-2");

      // ASSERT: Cache has hash type
      assertThat(cacheCmd.type("user:123")).isEqualTo("hash");
      assertThat(cacheCmd.hget("user:123", "name")).isEqualTo("John Doe");

      // ASSERT: Session has string type with TTL
      assertThat(sessionCmd.type("session:abc")).isEqualTo("string");
      assertThat(sessionCmd.ttl("session:abc")).isGreaterThan(0);

      // ASSERT: Rate-limiter has sorted set type
      assertThat(rateLimiterCmd.type("api:requests")).isEqualTo("zset");
      assertThat(rateLimiterCmd.zcard("api:requests")).isEqualTo(2L);
    }
  }

  @Test
  @DisplayName("Should handle high-volume writes to each instance independently")
  void shouldHandleHighVolumeWrites(final List<RedisConnectionInfo> instances) {
    final RedisConnectionInfo cache = instances.get(0);
    final RedisConnectionInfo session = instances.get(1);
    final RedisConnectionInfo rateLimiter = instances.get(2);

    try (final RedisClient cacheClient = createClient(cache);
        final RedisClient sessionClient = createClient(session);
        final RedisClient rateLimiterClient = createClient(rateLimiter);
        final StatefulRedisConnection<String, String> cacheConn = cacheClient.connect();
        final StatefulRedisConnection<String, String> sessionConn = sessionClient.connect();
        final StatefulRedisConnection<String, String> rateLimiterConn =
            rateLimiterClient.connect()) {

      final RedisCommands<String, String> cacheCmd = cacheConn.sync();
      final RedisCommands<String, String> sessionCmd = sessionConn.sync();
      final RedisCommands<String, String> rateLimiterCmd = rateLimiterConn.sync();

      // ACT: Write 1000 keys to each instance
      for (int i = 0; i < 1000; i++) {
        cacheCmd.set("cache:key:" + i, "cache-value-" + i);
        sessionCmd.set("session:key:" + i, "session-value-" + i);
        rateLimiterCmd.set("limit:key:" + i, "limit-value-" + i);
      }

      // ASSERT: Each instance has exactly 1000 keys
      assertThat(cacheCmd.dbsize()).isEqualTo(1000L);
      assertThat(sessionCmd.dbsize()).isEqualTo(1000L);
      assertThat(rateLimiterCmd.dbsize()).isEqualTo(1000L);

      // ASSERT: Random key verification (isolation)
      assertThat(cacheCmd.get("cache:key:500")).isEqualTo("cache-value-500");
      assertThat(sessionCmd.get("session:key:500")).isEqualTo("session-value-500");
      assertThat(rateLimiterCmd.get("limit:key:500")).isEqualTo("limit-value-500");

      // ASSERT: Cross-contamination check (cache doesn't have session keys)
      assertThat(cacheCmd.get("session:key:500")).isNull();
      assertThat(cacheCmd.get("limit:key:500")).isNull();
    }
  }

  @Test
  @DisplayName("Should work with different Redis versions (7.4 and 7.2)")
  void shouldWorkWithDifferentVersions() {
    final RedisConnectionInfo cache = RedisStandalone.INSTANCE.get("cache"); // 7.4
    final RedisConnectionInfo session = RedisStandalone.INSTANCE.get("session"); // 7.2

    try (final RedisClient cacheClient = createClient(cache);
        final RedisClient sessionClient = createClient(session);
        final StatefulRedisConnection<String, String> cacheConn = cacheClient.connect();
        final StatefulRedisConnection<String, String> sessionConn = sessionClient.connect()) {

      final RedisCommands<String, String> cacheCmd = cacheConn.sync();
      final RedisCommands<String, String> sessionCmd = sessionConn.sync();

      // ACT: Get Redis version info
      final String cacheInfo = cacheCmd.info("server");
      final String sessionInfo = sessionCmd.info("server");

      // ASSERT: Both versions are running
      assertThat(cacheInfo).contains("redis_version");
      assertThat(sessionInfo).contains("redis_version");

      // ACT: Verify both can execute commands
      cacheCmd.set("version-test", "7.4-data");
      sessionCmd.set("version-test", "7.2-data");

      // ASSERT: Data isolation across different versions
      assertThat(cacheCmd.get("version-test")).isEqualTo("7.4-data");
      assertThat(sessionCmd.get("version-test")).isEqualTo("7.2-data");
    }
  }

  // ==================== Helper Methods ====================

  private RedisClient createClient(final RedisConnectionInfo info) {
    final String uri = String.format("redis://%s:%d", info.getHost(), info.getPort());
    return RedisClient.create(uri);
  }
}
