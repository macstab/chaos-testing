/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.control.inspection.ConnectionInfo;
import com.macstab.chaos.redis.extension.SentinelCluster;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Exercises {@link com.macstab.chaos.redis.control.inspection.LettuceConnectionInspector} Tier 2
 * (hint) and Tier 3 (manual) paths via a real Sentinel cluster.
 *
 * <p>Also exercises {@link com.macstab.chaos.redis.control.inspection.RoleDetector} and {@link
 * com.macstab.chaos.redis.control.role.RoleResolver} with real containers.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisSentinel(id = "inspect-cluster", masterName = "mymaster", replicas = 1, sentinels = 1)
@DisplayName("LettuceConnectionInspector — Sentinel Integration")
class SentinelConnectionInspectionTest {

  @Nested
  @DisplayName("Tier 2: Inspect with container hint")
  class InspectWithHintTests {

    @Test
    @DisplayName("should inspect master connection with explicit hint")
    void shouldInspectMasterWithHint(final SentinelCluster cluster) {
      final RedisClient client = RedisClient.create(cluster.getMasterURI());
      try (final StatefulRedisConnection<String, String> conn = client.connect()) {
        final ConnectionInfo info =
            cluster.getControl().inspect(conn, cluster.getMasterContainer());
        assertThat(info).isNotNull();
        assertThat(info.container()).isEqualTo(cluster.getMasterContainer());
      } finally {
        client.shutdown();
      }
    }
  }

  @Nested
  @DisplayName("Tier 3: Manual inspection")
  class ManualInspectionTests {

    @Test
    @DisplayName("should create manual ConnectionInfo for master")
    void shouldInspectMasterManually(final SentinelCluster cluster) {
      final ConnectionInfo info =
          cluster.getControl().inspectManual(cluster.getMasterContainer(), "master connection");
      assertThat(info).isNotNull();
      assertThat(info.container()).isEqualTo(cluster.getMasterContainer());
    }

    @Test
    @DisplayName("should create manual ConnectionInfo for replica")
    void shouldInspectReplicaManually(final SentinelCluster cluster) {
      final var replica = cluster.getReplicaContainers().get(0);
      final ConnectionInfo info = cluster.getControl().inspectManual(replica, "replica connection");
      assertThat(info).isNotNull();
      assertThat(info.container()).isEqualTo(replica);
    }
  }

  @Nested
  @DisplayName("Tier 1: Auto-detection")
  class AutoDetectionTests {

    @Test
    @DisplayName("should auto-detect master container from Lettuce connection")
    void shouldAutoDetectMaster(final SentinelCluster cluster) {
      final RedisClient client = RedisClient.create(cluster.getMasterURI());
      try (final StatefulRedisConnection<String, String> conn = client.connect()) {
        // Tier 1: no hint — auto-detects from connection.toString()
        final ConnectionInfo info = cluster.inspect(conn);
        assertThat(info).isNotNull();
        assertThat(info.container()).isEqualTo(cluster.getMasterContainer());
      } finally {
        client.shutdown();
      }
    }
  }
}
