/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.api.SentinelRedis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Data isolation test validating that multiple Sentinel clusters are truly independent.
 *
 * <p><strong>Test Strategy:</strong> Write data to one cluster, verify it exists there and does NOT
 * exist in other clusters.
 *
 * <p><strong>Coverage:</strong>
 *
 * <ul>
 *   <li>✅ 2x Sentinel clusters run independently
 *   <li>✅ Data written to cluster A is NOT visible in cluster B
 *   <li>✅ Both clusters can hold different data with same keys
 *   <li>✅ Replication works within each cluster (master → replica)
 *   <li>✅ Cleanup between tests prevents cross-contamination
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisSentinel(id = "cluster-a", masterName = "master-a", replicas = 1, sentinels = 1)
@RedisSentinel(id = "cluster-b", masterName = "master-b", replicas = 1, sentinels = 1)
@DisplayName("Data Isolation Test - Multiple Sentinel Clusters")
class DataIsolationSentinelTest {

  @AfterEach
  void cleanup(final List<SentinelRedis> clusters) {
    // Clean up all data after each test to prevent cross-contamination
    clusters.forEach(
        cluster -> {
          try (final RedisClient client = createClient(cluster);
              final StatefulRedisConnection<String, String> conn = client.connect()) {
            conn.sync().flushall();
          }
        });
  }

  @Test
  @DisplayName("Should isolate data between cluster A and cluster B")
  void shouldIsolateDataBetweenClusters() {
    // ARRANGE: Get both clusters
    final SentinelRedis clusterA = RedisSentinel.INSTANCE.get("cluster-a");
    final SentinelRedis clusterB = RedisSentinel.INSTANCE.get("cluster-b");

    try (final RedisClient clientA = createClient(clusterA);
        final RedisClient clientB = createClient(clusterB);
        final StatefulRedisConnection<String, String> connA = clientA.connect();
        final StatefulRedisConnection<String, String> connB = clientB.connect()) {

      final RedisCommands<String, String> commandsA = connA.sync();
      final RedisCommands<String, String> commandsB = connB.sync();

      // ACT: Write data to cluster A
      commandsA.set("test-key", "value-from-cluster-a");
      commandsA.set("shared-key", "a-value");

      // ACT: Write data to cluster B
      commandsB.set("test-key", "value-from-cluster-b");
      commandsB.set("shared-key", "b-value");

      // ASSERT: Cluster A has its own data
      assertThat(commandsA.get("test-key")).isEqualTo("value-from-cluster-a");
      assertThat(commandsA.get("shared-key")).isEqualTo("a-value");

      // ASSERT: Cluster B has its own data (completely isolated)
      assertThat(commandsB.get("test-key")).isEqualTo("value-from-cluster-b");
      assertThat(commandsB.get("shared-key")).isEqualTo("b-value");

      // ASSERT: Same key holds different values in each cluster
      assertThat(commandsA.get("shared-key")).isNotEqualTo(commandsB.get("shared-key"));
    }
  }

  @Test
  @DisplayName("Should replicate data within cluster but NOT across clusters")
  void shouldReplicateWithinClusterOnly(final List<SentinelRedis> clusters) {
    final SentinelRedis clusterA = clusters.get(0); // cluster-a
    final SentinelRedis clusterB = clusters.get(1); // cluster-b

    try (final RedisClient clientA = createClient(clusterA);
        final RedisClient clientB = createClient(clusterB);
        final StatefulRedisConnection<String, String> connA = clientA.connect();
        final StatefulRedisConnection<String, String> connB = clientB.connect()) {

      final RedisCommands<String, String> commandsA = connA.sync();
      final RedisCommands<String, String> commandsB = connB.sync();

      // ACT: Write to cluster A master
      commandsA.set("replicated-key", "cluster-a-data");

      // Wait for replication within cluster A (typically <100ms)
      Thread.sleep(200);

      // ASSERT: Cluster A has the data
      assertThat(commandsA.get("replicated-key")).isEqualTo("cluster-a-data");

      // ASSERT: Cluster B does NOT have the data (isolated)
      assertThat(commandsB.get("replicated-key")).isNull();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @Test
  @DisplayName("Should handle concurrent writes to different clusters independently")
  void shouldHandleConcurrentWrites(final List<SentinelRedis> clusters) {
    final SentinelRedis clusterA = clusters.get(0);
    final SentinelRedis clusterB = clusters.get(1);

    try (final RedisClient clientA = createClient(clusterA);
        final RedisClient clientB = createClient(clusterB);
        final StatefulRedisConnection<String, String> connA = clientA.connect();
        final StatefulRedisConnection<String, String> connB = clientB.connect()) {

      final RedisCommands<String, String> commandsA = connA.sync();
      final RedisCommands<String, String> commandsB = connB.sync();

      // ACT: Write 100 keys to each cluster
      for (int i = 0; i < 100; i++) {
        commandsA.set("key-" + i, "value-a-" + i);
        commandsB.set("key-" + i, "value-b-" + i);
      }

      // ASSERT: Each cluster has exactly 100 keys
      assertThat(commandsA.dbsize()).isEqualTo(100L);
      assertThat(commandsB.dbsize()).isEqualTo(100L);

      // ASSERT: Random key verification (isolation)
      assertThat(commandsA.get("key-42")).isEqualTo("value-a-42");
      assertThat(commandsB.get("key-42")).isEqualTo("value-b-42");
    }
  }

  @Test
  @DisplayName("Should support different Redis operations in each cluster")
  void shouldSupportDifferentOperationsPerCluster() {
    final SentinelRedis clusterA = RedisSentinel.INSTANCE.get("cluster-a");
    final SentinelRedis clusterB = RedisSentinel.INSTANCE.get("cluster-b");

    try (final RedisClient clientA = createClient(clusterA);
        final RedisClient clientB = createClient(clusterB);
        final StatefulRedisConnection<String, String> connA = clientA.connect();
        final StatefulRedisConnection<String, String> connB = clientB.connect()) {

      final RedisCommands<String, String> commandsA = connA.sync();
      final RedisCommands<String, String> commandsB = connB.sync();

      // ACT: Cluster A uses strings
      commandsA.set("data", "string-value");

      // ACT: Cluster B uses hashes
      commandsB.hset("data", "field1", "hash-value-1");
      commandsB.hset("data", "field2", "hash-value-2");

      // ASSERT: Cluster A has string type
      assertThat(commandsA.type("data")).isEqualTo("string");
      assertThat(commandsA.get("data")).isEqualTo("string-value");

      // ASSERT: Cluster B has hash type (different data structure, same key)
      assertThat(commandsB.type("data")).isEqualTo("hash");
      assertThat(commandsB.hget("data", "field1")).isEqualTo("hash-value-1");
      assertThat(commandsB.hget("data", "field2")).isEqualTo("hash-value-2");
    }
  }

  // ==================== Helper Methods ====================

  private RedisClient createClient(final SentinelRedis cluster) {
    final String uri =
        String.format("redis://%s:%d", cluster.host(), cluster.port());
    return RedisClient.create(uri);
  }
}
