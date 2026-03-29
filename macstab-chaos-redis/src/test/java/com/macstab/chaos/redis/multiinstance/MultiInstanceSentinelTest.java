/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.api.SentinelRedis;

/**
 * Meta-test validating multi-instance Sentinel cluster support.
 *
 * <p>Tests the v2.0 multi-instance capabilities:
 *
 * <ul>
 *   <li>Multiple {@code @RedisSentinel} annotations
 *   <li>Parallel cluster startup
 *   <li>Parameter injection via {@code List<SentinelRedis>}
 *   <li>Programmatic access via {@code RedisSentinel.INSTANCE.get(id)}
 *   <li>Ordering guarantees (declaration order)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisSentinel(id = "first", masterName = "master-first", replicas = 1, sentinels = 1)
@RedisSentinel(id = "second", masterName = "master-second", replicas = 1, sentinels = 1)
@RedisSentinel(id = "third", masterName = "master-third", replicas = 1, sentinels = 1)
@DisplayName("Multi-Instance Sentinel Test - Parameter Injection & Ordering")
class MultiInstanceSentinelTest {

  @Test
  @DisplayName("Should inject all 3 clusters via List<SentinelRedis> parameter")
  void shouldInjectAllClusters(final List<SentinelRedis> clusters) {
    // ASSERT: Exactly 3 clusters
    assertThat(clusters).hasSize(3);

    // ASSERT: All clusters have valid connection info
    clusters.forEach(
        cluster -> {
          assertThat(cluster.host()).isNotEmpty();
          assertThat(cluster.port()).isGreaterThan(0);
          assertThat(cluster.masterName()).isNotEmpty();
        });
  }

  @Test
  @DisplayName("Should maintain annotation declaration order in List<SentinelRedis>")
  void shouldMaintainDeclarationOrder(final List<SentinelRedis> clusters) {
    // ASSERT: Order matches declaration (not sorted by ID)
    assertThat(clusters).hasSize(3);

    // First annotation: id="first", masterName="master-first"
    assertThat(clusters.get(0).masterName()).isEqualTo("master-first");

    // Second annotation: id="second", masterName="master-second"
    assertThat(clusters.get(1).masterName()).isEqualTo("master-second");

    // Third annotation: id="third", masterName="master-third"
    assertThat(clusters.get(2).masterName()).isEqualTo("master-third");
  }

  @Test
  @DisplayName("Should access individual clusters by ID programmatically")
  void shouldAccessByIdProgrammatically() {
    // ACT: Get clusters by ID
    final SentinelRedis first = RedisSentinel.INSTANCE.get("first");
    final SentinelRedis second = RedisSentinel.INSTANCE.get("second");
    final SentinelRedis third = RedisSentinel.INSTANCE.get("third");

    // ASSERT: All clusters exist
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(third).isNotNull();

    // ASSERT: Master names match configuration
    assertThat(first.masterName()).isEqualTo("master-first");
    assertThat(second.masterName()).isEqualTo("master-second");
    assertThat(third.masterName()).isEqualTo("master-third");

    // ASSERT: They are distinct instances (different ports)
    assertThat(first.port()).isNotEqualTo(second.port());
    assertThat(second.port()).isNotEqualTo(third.port());
  }

  @Test
  @DisplayName("Should access all clusters via INSTANCE.getAll()")
  void shouldGetAllProgrammatically() {
    // ACT: Get all clusters
    final List<SentinelRedis> all = RedisSentinel.INSTANCE.getAll();

    // ASSERT: Size matches annotation count
    assertThat(all).hasSize(3);

    // ASSERT: Ordering matches declaration
    assertThat(all)
        .extracting(SentinelRedis::masterName)
        .containsExactly("master-first", "master-second", "master-third");

    // ASSERT: All clusters have expected topology (1 replica, 1 sentinel each)
    all.forEach(
        cluster -> {
          assertThat(cluster.replicas()).hasSize(1);
          assertThat(cluster.sentinels()).hasSize(1);
        });
  }

  @Test
  @DisplayName("Should provide connection info for all clusters")
  void shouldProvideConnectionInfo(final List<SentinelRedis> clusters) {
    // ASSERT: Each cluster provides valid connection info
    clusters.forEach(
        cluster -> {
          // Master connection
          assertThat(cluster.host()).isNotEmpty();
          assertThat(cluster.port()).isGreaterThan(0);

          // Replica connections
          assertThat(cluster.replicas()).hasSize(1);
          cluster
              .replicas()
              .forEach(
                  replica -> {
                    assertThat(replica.host()).isNotEmpty();
                    assertThat(replica.port()).isGreaterThan(0);
                  });

          // Sentinel connections
          assertThat(cluster.sentinels()).hasSize(1);
          cluster
              .sentinels()
              .forEach(
                  sentinel -> {
                    assertThat(sentinel.host()).isNotEmpty();
                    assertThat(sentinel.port()).isGreaterThan(0);
                  });
        });
  }
}
