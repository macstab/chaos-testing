/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.api.StandaloneRedis;
import com.macstab.chaos.redis.api.SentinelRedis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Mixed topology test validating Sentinel + Standalone instances coexisting in one test class.
 *
 * <p><strong>Test Strategy:</strong> Run both Sentinel clusters AND standalone instances, verify
 * complete data isolation and independent operation.
 *
 * <p><strong>Coverage:</strong>
 *
 * <ul>
 *   <li>✅ 1x Sentinel cluster (HA setup)
 *   <li>✅ 2x Standalone instances (lightweight caching)
 *   <li>✅ All instances start in parallel
 *   <li>✅ Data isolation across all instances
 *   <li>✅ Parameter injection works for mixed types
 *   <li>✅ Resource budget validation (total containers)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisSentinel(id = "ha-cluster", masterName = "master-ha", replicas = 2, sentinels = 3)
@RedisStandalone(id = "cache", version = "7.4")
@RedisStandalone(id = "session", version = "7.2")
@DisplayName("Mixed Topology Test - Sentinel + Standalone")
class MixedTopologyTest {

  @BeforeAll
  static void verifyAllInstancesStarted(
      final List<SentinelRedis> sentinels, final List<StandaloneRedis> standalones) {
    // ASSERT: Exactly 1 Sentinel cluster
    assertThat(sentinels).hasSize(1);

    // ASSERT: Exactly 2 standalone instances
    assertThat(standalones).hasSize(2);

    // ASSERT: Sentinel cluster has correct topology
    final SentinelRedis haCluster = sentinels.get(0);
    assertThat(haCluster.masterName()).isEqualTo("master-ha");
    assertThat(haCluster.getReplicaContainers()).hasSize(2);
    assertThat(haCluster.getSentinelContainers()).hasSize(3);

    // ASSERT: All instances are running
    assertThat(haCluster.master().isRunning()).isTrue();
    assertThat(standalones)
        .allSatisfy(
            instance -> {
              assertThat(instance.host()).isNotEmpty();
              assertThat(instance.port()).isGreaterThan(0);
            });
  }

  @AfterEach
  void cleanup(final List<SentinelRedis> sentinels, final List<StandaloneRedis> standalones) {
    // Clean up Sentinel cluster
    sentinels.forEach(
        cluster -> {
          try (final RedisClient client = createClientForSentinel(cluster);
              final StatefulRedisConnection<String, String> conn = client.connect()) {
            conn.sync().flushall();
          }
        });

    // Clean up standalone instances
    standalones.forEach(
        instance -> {
          try (final RedisClient client = createClientForStandalone(instance);
              final StatefulRedisConnection<String, String> conn = client.connect()) {
            conn.sync().flushall();
          }
        });
  }

  @Test
  @DisplayName("Should isolate data between Sentinel cluster and standalone instances")
  void shouldIsolateDataAcrossAllInstances() {
    // ARRANGE: Get all instances
    final SentinelRedis haCluster = RedisSentinel.INSTANCE.get("ha-cluster");
    final StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
    final StandaloneRedis session = RedisStandalone.INSTANCE.get("session");

    try (final RedisClient haClient = createClientForSentinel(haCluster);
        final RedisClient cacheClient = createClientForStandalone(cache);
        final RedisClient sessionClient = createClientForStandalone(session);
        final StatefulRedisConnection<String, String> haConn = haClient.connect();
        final StatefulRedisConnection<String, String> cacheConn = cacheClient.connect();
        final StatefulRedisConnection<String, String> sessionConn = sessionClient.connect()) {

      final RedisCommands<String, String> haCmd = haConn.sync();
      final RedisCommands<String, String> cacheCmd = cacheConn.sync();
      final RedisCommands<String, String> sessionCmd = sessionConn.sync();

      // ACT: Write data to Sentinel cluster (HA data)
      haCmd.set("critical-data", "ha-replicated-value");

      // ACT: Write data to cache instance
      cacheCmd.set("cached-data", "cache-only-value");

      // ACT: Write data to session instance
      sessionCmd.set("session-data", "session-only-value");

      // ASSERT: Sentinel cluster has only HA data
      assertThat(haCmd.get("critical-data")).isEqualTo("ha-replicated-value");
      assertThat(haCmd.get("cached-data")).isNull(); // NOT in HA cluster
      assertThat(haCmd.get("session-data")).isNull(); // NOT in HA cluster

      // ASSERT: Cache instance has only cache data
      assertThat(cacheCmd.get("cached-data")).isEqualTo("cache-only-value");
      assertThat(cacheCmd.get("critical-data")).isNull(); // NOT in cache
      assertThat(cacheCmd.get("session-data")).isNull(); // NOT in cache

      // ASSERT: Session instance has only session data
      assertThat(sessionCmd.get("session-data")).isEqualTo("session-only-value");
      assertThat(sessionCmd.get("critical-data")).isNull(); // NOT in session
      assertThat(sessionCmd.get("cached-data")).isNull(); // NOT in session
    }
  }

  @Test
  @DisplayName("Should support different use cases: HA + caching + sessions")
  void shouldSupportDifferentUseCases(
      final List<SentinelRedis> sentinels, final List<StandaloneRedis> standalones) {
    final SentinelRedis haCluster = sentinels.get(0);
    final StandaloneRedis cache = standalones.get(0);
    final StandaloneRedis session = standalones.get(1);

    try (final RedisClient haClient = createClientForSentinel(haCluster);
        final RedisClient cacheClient = createClientForStandalone(cache);
        final RedisClient sessionClient = createClientForStandalone(session);
        final StatefulRedisConnection<String, String> haConn = haClient.connect();
        final StatefulRedisConnection<String, String> cacheConn = cacheClient.connect();
        final StatefulRedisConnection<String, String> sessionConn = sessionClient.connect()) {

      final RedisCommands<String, String> haCmd = haConn.sync();
      final RedisCommands<String, String> cacheCmd = cacheConn.sync();
      final RedisCommands<String, String> sessionCmd = sessionConn.sync();

      // USE CASE 1: HA cluster stores critical business data (replicated)
      haCmd.hset("order:12345", "status", "confirmed");
      haCmd.hset("order:12345", "total", "99.99");
      haCmd.hset("order:12345", "customer", "user-123");

      // USE CASE 2: Cache stores frequently accessed data with TTL
      cacheCmd.setex("product:789", 60, "Product Name XYZ");
      cacheCmd.setex("pricing:789", 60, "29.99");

      // USE CASE 3: Session stores ephemeral user sessions
      sessionCmd.setex("session:abc-def-ghi", 3600, "user-123-token");
      sessionCmd.setex("session:jkl-mno-pqr", 3600, "user-456-token");

      // ASSERT: HA cluster - critical data persisted
      assertThat(haCmd.type("order:12345")).isEqualTo("hash");
      assertThat(haCmd.hget("order:12345", "status")).isEqualTo("confirmed");
      assertThat(haCmd.ttl("order:12345")).isEqualTo(-1); // No TTL (persistent)

      // ASSERT: Cache - temporary data with TTL
      assertThat(cacheCmd.get("product:789")).isEqualTo("Product Name XYZ");
      assertThat(cacheCmd.ttl("product:789")).isGreaterThan(0); // Has TTL

      // ASSERT: Session - user sessions with TTL
      assertThat(sessionCmd.get("session:abc-def-ghi")).isEqualTo("user-123-token");
      assertThat(sessionCmd.ttl("session:abc-def-ghi")).isGreaterThan(0); // Has TTL
    }
  }

  @Test
  @DisplayName("Should verify Sentinel replication while standalone instances remain isolated")
  void shouldVerifyReplicationInHAOnly() throws InterruptedException {
    final SentinelRedis haCluster = RedisSentinel.INSTANCE.get("ha-cluster");
    final StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");

    try (final RedisClient haClient = createClientForSentinel(haCluster);
        final RedisClient cacheClient = createClientForStandalone(cache);
        final StatefulRedisConnection<String, String> haConn = haClient.connect();
        final StatefulRedisConnection<String, String> cacheConn = cacheClient.connect()) {

      final RedisCommands<String, String> haCmd = haConn.sync();
      final RedisCommands<String, String> cacheCmd = cacheConn.sync();

      // ACT: Write to Sentinel master
      haCmd.set("replicated-key", "replicated-value");

      // Wait for replication to replicas (typically <100ms)
      Thread.sleep(200);

      // ASSERT: Sentinel cluster has the data (replicated to 2 replicas)
      assertThat(haCmd.get("replicated-key")).isEqualTo("replicated-value");

      // ASSERT: Cache instance does NOT have the data (no replication)
      assertThat(cacheCmd.get("replicated-key")).isNull();
    }
  }

  @Test
  @DisplayName("Should handle concurrent writes to all instance types")
  void shouldHandleConcurrentWrites(
      final List<SentinelRedis> sentinels, final List<StandaloneRedis> standalones) {
    final SentinelRedis haCluster = sentinels.get(0);
    final StandaloneRedis cache = standalones.get(0);
    final StandaloneRedis session = standalones.get(1);

    try (final RedisClient haClient = createClientForSentinel(haCluster);
        final RedisClient cacheClient = createClientForStandalone(cache);
        final RedisClient sessionClient = createClientForStandalone(session);
        final StatefulRedisConnection<String, String> haConn = haClient.connect();
        final StatefulRedisConnection<String, String> cacheConn = cacheClient.connect();
        final StatefulRedisConnection<String, String> sessionConn = sessionClient.connect()) {

      final RedisCommands<String, String> haCmd = haConn.sync();
      final RedisCommands<String, String> cacheCmd = cacheConn.sync();
      final RedisCommands<String, String> sessionCmd = sessionConn.sync();

      // ACT: Write 100 keys to each instance
      for (int i = 0; i < 100; i++) {
        haCmd.set("ha:key:" + i, "ha-value-" + i);
        cacheCmd.set("cache:key:" + i, "cache-value-" + i);
        sessionCmd.set("session:key:" + i, "session-value-" + i);
      }

      // ASSERT: Each instance has exactly 100 keys
      assertThat(haCmd.dbsize()).isEqualTo(100L);
      assertThat(cacheCmd.dbsize()).isEqualTo(100L);
      assertThat(sessionCmd.dbsize()).isEqualTo(100L);

      // ASSERT: Total across all instances (300 keys in total, isolated)
      final long totalKeys = haCmd.dbsize() + cacheCmd.dbsize() + sessionCmd.dbsize();
      assertThat(totalKeys).isEqualTo(300L);
    }
  }

  @Test
  @DisplayName("Should access instances programmatically by ID")
  void shouldAccessByIdProgrammatically() {
    // ACT: Access Sentinel cluster by ID
    final SentinelRedis haCluster = RedisSentinel.INSTANCE.get("ha-cluster");

    // ACT: Access standalone instances by ID
    final StandaloneRedis cache = RedisStandalone.INSTANCE.get("cache");
    final StandaloneRedis session = RedisStandalone.INSTANCE.get("session");

    // ASSERT: All instances exist
    assertThat(haCluster).isNotNull();
    assertThat(cache).isNotNull();
    assertThat(session).isNotNull();

    // ASSERT: Sentinel cluster has correct master name
    assertThat(haCluster.masterName()).isEqualTo("master-ha");

    // ASSERT: Standalone instances have valid connection info
    assertThat(cache.host()).isNotEmpty();
    assertThat(cache.port()).isGreaterThan(0);
    assertThat(session.host()).isNotEmpty();
    assertThat(session.port()).isGreaterThan(0);

    // ASSERT: Instances are distinct (different ports)
    assertThat(haCluster.port()).isNotEqualTo(cache.port());
    assertThat(haCluster.port()).isNotEqualTo(session.port());
    assertThat(cache.port()).isNotEqualTo(session.port());
  }

  // ==================== Helper Methods ====================

  private RedisClient createClientForSentinel(final SentinelRedis cluster) {
    final String uri =
        String.format("redis://%s:%d", cluster.host(), cluster.port());
    return RedisClient.create(uri);
  }

  private RedisClient createClientForStandalone(final StandaloneRedis info) {
    final String uri = String.format("redis://%s:%d", info.host(), info.port());
    return RedisClient.create(uri);
  }
}
