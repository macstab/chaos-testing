/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.extension.SentinelContainerExtension.SentinelCluster;

/**
 * Meta-test validating multi-instance Sentinel cluster support.
 *
 * <p>Tests the v2.0 multi-instance capabilities:
 *
 * <ul>
 *   <li>Multiple {@code @RedisSentinel} annotations
 *   <li>Parallel cluster startup
 *   <li>Parameter injection via {@code List<SentinelCluster>}
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
  @DisplayName("Should inject all 3 clusters via List<SentinelCluster> parameter")
  void shouldInjectAllClusters(final List<SentinelCluster> clusters) {
    // ASSERT: Exactly 3 clusters
    assertThat(clusters).hasSize(3);

    // ASSERT: All clusters are running
    clusters.forEach(
        cluster -> {
          assertThat(cluster.getMasterContainer()).isNotNull();
          assertThat(cluster.getMasterContainer().isRunning()).isTrue();
        });
  }

  @Test
  @DisplayName("Should maintain annotation declaration order in List<SentinelCluster>")
  void shouldMaintainDeclarationOrder(final List<SentinelCluster> clusters) {
    // ASSERT: Order matches declaration (not sorted by ID)
    assertThat(clusters).hasSize(3);

    // First annotation: id="first", masterName="master-first"
    assertThat(clusters.get(0).getMasterName()).isEqualTo("master-first");

    // Second annotation: id="second", masterName="master-second"
    assertThat(clusters.get(1).getMasterName()).isEqualTo("master-second");

    // Third annotation: id="third", masterName="master-third"
    assertThat(clusters.get(2).getMasterName()).isEqualTo("master-third");
  }

  @Test
  @DisplayName("Should access individual clusters by ID programmatically")
  void shouldAccessByIdProgrammatically() {
    // ACT: Get clusters by ID
    final SentinelCluster first = RedisSentinel.INSTANCE.get("first");
    final SentinelCluster second = RedisSentinel.INSTANCE.get("second");
    final SentinelCluster third = RedisSentinel.INSTANCE.get("third");

    // ASSERT: All clusters exist
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(third).isNotNull();

    // ASSERT: Master names match configuration
    assertThat(first.getMasterName()).isEqualTo("master-first");
    assertThat(second.getMasterName()).isEqualTo("master-second");
    assertThat(third.getMasterName()).isEqualTo("master-third");

    // ASSERT: They are distinct instances (different ports)
    assertThat(first.getMasterPort()).isNotEqualTo(second.getMasterPort());
    assertThat(second.getMasterPort()).isNotEqualTo(third.getMasterPort());
  }

  @Test
  @DisplayName("Should access all clusters via INSTANCE.getAll()")
  void shouldGetAllProgrammatically() {
    // ACT: Get all clusters
    final List<SentinelCluster> all = RedisSentinel.INSTANCE.getAll();

    // ASSERT: Size matches annotation count
    assertThat(all).hasSize(3);

    // ASSERT: Ordering matches declaration
    assertThat(all)
        .extracting(SentinelCluster::getMasterName)
        .containsExactly("master-first", "master-second", "master-third");

    // ASSERT: All clusters have expected topology (1 replica, 1 sentinel each)
    all.forEach(
        cluster -> {
          assertThat(cluster.getReplicaContainers()).hasSize(1);
          assertThat(cluster.getSentinelContainers()).hasSize(1);
        });
  }

  @Test
  @DisplayName("Should provide connection info for all clusters")
  void shouldProvideConnectionInfo(final List<SentinelCluster> clusters) {
    // ASSERT: Each cluster provides valid connection info
    clusters.forEach(
        cluster -> {
          // Master connection
          assertThat(cluster.getMasterHost()).isNotEmpty();
          assertThat(cluster.getMasterPort()).isGreaterThan(0);

          // Replica connections
          assertThat(cluster.getReplicas()).hasSize(1);
          cluster
              .getReplicas()
              .forEach(
                  replica -> {
                    assertThat(replica.getHost()).isNotEmpty();
                    assertThat(replica.getPort()).isGreaterThan(0);
                  });

          // Sentinel connections
          assertThat(cluster.getSentinels()).hasSize(1);
          cluster
              .getSentinels()
              .forEach(
                  sentinel -> {
                    assertThat(sentinel.getHost()).isNotEmpty();
                    assertThat(sentinel.getPort()).isGreaterThan(0);
                  });
        });
  }
}
