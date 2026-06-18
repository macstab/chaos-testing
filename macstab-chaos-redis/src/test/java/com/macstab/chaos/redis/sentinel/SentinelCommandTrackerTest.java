/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.extension.SentinelCluster;
import com.macstab.chaos.redis.util.RedisCommandTracker;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Exercises {@link RedisCommandTracker} against a real Sentinel master, and {@link
 * RedisCommandTracker#measureReplicationLag} across master/replica.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisSentinel(id = "tracker-cluster", masterName = "mymaster", replicas = 1, sentinels = 1)
@DisplayName("RedisCommandTracker — Sentinel Integration")
class SentinelCommandTrackerTest {

  @Nested
  @DisplayName("MONITOR command capture on master")
  class MonitorCaptureTests {

    @Test
    @DisplayName("should capture SET commands on master")
    void shouldCaptureSetOnMaster(final SentinelCluster cluster) throws Exception {
      final RedisCommandTracker tracker = new RedisCommandTracker(cluster.getMasterContainer());
      tracker.start();

      final RedisClient client = RedisClient.create(cluster.getMasterURI());
      try (final StatefulRedisConnection<String, String> conn = client.connect()) {
        final RedisCommands<String, String> cmd = conn.sync();
        cmd.set("tracker-key-1", "val1");
        cmd.set("tracker-key-2", "val2");
      } finally {
        client.shutdown();
      }

      Thread.sleep(300);
      tracker.stop();

      assertThat(tracker.countCommand("SET")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("should match key pattern on master")
    void shouldMatchKeyPattern(final SentinelCluster cluster) throws Exception {
      final RedisCommandTracker tracker = new RedisCommandTracker(cluster.getMasterContainer());
      tracker.start();

      final RedisClient client = RedisClient.create(cluster.getMasterURI());
      try (final StatefulRedisConnection<String, String> conn = client.connect()) {
        final RedisCommands<String, String> cmd = conn.sync();
        cmd.set("user:123", "alice");
        cmd.set("product:456", "widget");
        cmd.get("user:123");
      } finally {
        client.shutdown();
      }

      Thread.sleep(300);
      tracker.stop();

      assertThat(tracker.countCommandsMatchingKeyPattern("GET", "user:*"))
          .isGreaterThanOrEqualTo(1);
      assertThat(tracker.countCommandsMatchingKeyPattern("GET", "product:*")).isZero();
    }
  }

  @Nested
  @DisplayName("Replication lag measurement")
  class ReplicationLagTests {

    @Test
    @DisplayName("should measure replication lag between master and replica")
    void shouldMeasureReplicationLag(final SentinelCluster cluster) {
      final var master = cluster.getMasterContainer();
      final var replica = cluster.getReplicaContainers().get(0);

      final Duration lag = RedisCommandTracker.measureReplicationLag(master, replica);

      assertThat(lag).isNotNull();
      assertThat(lag.toMillis()).isGreaterThanOrEqualTo(0);
      assertThat(lag.toSeconds()).isLessThan(5);
    }
  }
}
