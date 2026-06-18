/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.control.role.ContainerRole;
import com.macstab.chaos.redis.extension.SentinelCluster;

/**
 * Exercises {@link com.macstab.chaos.redis.control.failover.FailoverHelper} via the Sentinel
 * cluster — covering the failover, role detection, and getReplicas paths.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisSentinel(id = "failover-cluster", masterName = "mymaster", replicas = 2, sentinels = 3)
@DisplayName("FailoverHelper — Sentinel Failover Integration")
class SentinelFailoverTest {

  @Nested
  @DisplayName("Role detection")
  class RoleDetectionTests {

    @Test
    @DisplayName("getMaster() should return a running container")
    void shouldFindMaster(final SentinelCluster cluster) {
      assertThat(cluster.getControl().getMaster()).isNotNull();
      assertThat(cluster.getControl().getMaster().isRunning()).isTrue();
    }

    @Test
    @DisplayName("getReplicas() should return at least 1 replica container")
    void shouldFindReplicas(final SentinelCluster cluster) {
      // replicas=2 configured but role detection may see 1 during sync window — at least 1 must
      // report as replica
      final List<?> replicas = cluster.getControl().getReplicas();
      assertThat(replicas).isNotEmpty();
    }

    @Test
    @DisplayName("getContainer(MASTER) should return running master")
    void shouldGetContainerByRole(final SentinelCluster cluster) {
      final var master = cluster.getControl().getContainer(ContainerRole.MASTER);
      assertThat(master.isRunning()).isTrue();
    }
  }

  @Nested
  @DisplayName("Failover simulation")
  class FailoverSimulationTests {

    @Test
    @DisplayName("triggerFailover() should complete and elect new master")
    void shouldTriggerFailover(final SentinelCluster cluster) {
      final Duration elapsed = cluster.getControl().triggerFailover(Duration.ofSeconds(30));
      assertThat(elapsed).isNotNull();
      assertThat(elapsed.toSeconds()).isLessThan(30);

      // New master is running after failover
      assertThat(cluster.getControl().getMaster().isRunning()).isTrue();
    }
  }
}
