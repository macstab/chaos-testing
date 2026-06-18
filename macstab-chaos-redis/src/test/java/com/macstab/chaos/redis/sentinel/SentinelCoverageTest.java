/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.control.inspection.ConnectionInfo;
import com.macstab.chaos.redis.control.inspection.RoleDetector;
import com.macstab.chaos.redis.control.role.ContainerRole;
import com.macstab.chaos.redis.extension.SentinelCluster;
import com.macstab.chaos.redis.factory.SentinelCommandBuilder;

/**
 * Integration tests targeting uncovered code paths in Sentinel infrastructure.
 *
 * <p>Hits: ConnectionInfo fields, RoleDetector.detectAll(), ControlFacade access, FailoverHelper
 * getMaster/getReplicas, and SentinelCommandBuilder announce methods.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisSentinel(id = "cov", masterName = "mymaster", replicas = 1, sentinels = 1)
@DisplayName("Sentinel Coverage Integration")
class SentinelCoverageTest {

  @Test
  @DisplayName("Should expose all ConnectionInfo fields")
  void shouldExposeAllConnectionInfoFields(final SentinelCluster cluster) {
    final ConnectionInfo info =
        cluster.getControl().inspectManual(cluster.getMasterContainer(), "test");

    assertThat(info.container()).isNotNull();
    assertThat(info.role()).isNotNull();
    assertThat(info.connectionInfo()).isNotBlank();
    assertThat(info.healthy()).isTrue();
  }

  @Test
  @DisplayName("Should detect all roles via RoleDetector.detectAll()")
  void shouldDetectAllRoles(final SentinelCluster cluster) {
    final List<GenericContainer<?>> containers = new ArrayList<>();
    containers.add(cluster.getMasterContainer());
    containers.addAll(cluster.getReplicaContainers());

    final Map<GenericContainer<?>, ContainerRole> roles = RoleDetector.detectAll(containers);

    assertThat(roles).hasSize(2);
    assertThat(roles.get(cluster.getMasterContainer())).isNotNull();
  }

  @Test
  @DisplayName("Should find master and replicas via FailoverHelper")
  void shouldFindMasterAndReplicas(final SentinelCluster cluster) {
    final GenericContainer<?> master = cluster.getControl().getMaster();
    final List<GenericContainer<?>> replicas = cluster.getControl().getReplicas();

    assertThat(master.isRunning()).isTrue();
    assertThat(replicas).isNotEmpty();
  }

  @Test
  @DisplayName("Should get container by MASTER role")
  void shouldGetContainerByMasterRole(final SentinelCluster cluster) {
    final GenericContainer<?> masterContainer =
        cluster.getControl().getContainer(ContainerRole.MASTER);

    assertThat(masterContainer).isNotNull();
    assertThat(masterContainer.isRunning()).isTrue();
  }

  @Test
  @DisplayName("Should get container by SENTINEL_0 role")
  void shouldGetContainerBySentinelRole(final SentinelCluster cluster) {
    final GenericContainer<?> sentinelContainer =
        cluster.getControl().getContainer(ContainerRole.SENTINEL_0);

    assertThat(sentinelContainer).isNotNull();
    assertThat(sentinelContainer.isRunning()).isTrue();
  }

  @Test
  @DisplayName("configureMasterAnnouncement() should be idempotent")
  void shouldConfigureMasterAnnouncement(final SentinelCluster cluster) {
    assertThatCode(
            () -> SentinelCommandBuilder.configureMasterAnnouncement(cluster.getMasterContainer()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("configureReplicaAnnouncement() should be idempotent")
  void shouldConfigureReplicaAnnouncement(final SentinelCluster cluster) {
    final GenericContainer<?> replica = cluster.getReplicaContainers().get(0);

    assertThatCode(() -> SentinelCommandBuilder.configureReplicaAnnouncement(replica))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("configureSentinelAnnouncement() should be idempotent")
  void shouldConfigureSentinelAnnouncement(final SentinelCluster cluster) {
    final GenericContainer<?> sentinel = cluster.getSentinelContainers().get(0);

    assertThatCode(() -> SentinelCommandBuilder.configureSentinelAnnouncement(sentinel))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("ConnectionInfo.healthy() factory method should produce healthy info")
  void shouldCreateHealthyConnectionInfo(final SentinelCluster cluster) {
    final ConnectionInfo healthy =
        ConnectionInfo.healthy(ContainerRole.MASTER, cluster.getMasterContainer(), "manual-test");

    assertThat(healthy.healthy()).isTrue();
    assertThat(healthy.role()).isEqualTo(ContainerRole.MASTER);
    assertThat(healthy.connectionInfo()).isEqualTo("manual-test");
  }

  @Test
  @DisplayName("ConnectionInfo.unhealthy() factory method should produce unhealthy info")
  void shouldCreateUnhealthyConnectionInfo(final SentinelCluster cluster) {
    final ConnectionInfo unhealthy =
        ConnectionInfo.unhealthy(
            ContainerRole.REPLICA_0, cluster.getReplicaContainers().get(0), "test-description");

    assertThat(unhealthy.healthy()).isFalse();
    assertThat(unhealthy.role()).isEqualTo(ContainerRole.REPLICA_0);
  }
}
