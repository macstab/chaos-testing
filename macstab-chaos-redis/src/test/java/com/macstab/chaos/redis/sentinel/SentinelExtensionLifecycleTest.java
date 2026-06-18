/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.api.SentinelRedis;
import com.macstab.chaos.redis.extension.SentinelCluster;

/**
 * Exercises the full {@code @RedisSentinel} extension lifecycle.
 *
 * <p>Running this test class covers:
 *
 * <ul>
 *   <li>{@code SentinelContainerExtension.beforeAll/afterAll} (extension lifecycle)
 *   <li>{@code SentinelStartupOrchestrator.start()} (parallel cluster startup)
 *   <li>{@code SentinelCluster} construction, lifecycle, toSentinelRedis()
 *   <li>{@code ChaosTestingExtension.registerExternalConnectionInfo()} (INSTANCE.get path)
 *   <li>Parameter injection: {@code SentinelCluster}, {@code SentinelRedis}, {@code
 *       List<SentinelRedis>}
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisSentinel(id = "primary", masterName = "mymaster", replicas = 1, sentinels = 1)
@DisplayName("SentinelContainerExtension — Lifecycle Integration")
class SentinelExtensionLifecycleTest {

  @Nested
  @DisplayName("SentinelCluster parameter injection")
  class ClusterParameterInjectionTests {

    @Test
    @DisplayName("should inject SentinelCluster parameter")
    void shouldInjectCluster(final SentinelCluster cluster) {
      assertThat(cluster).isNotNull();
      assertThat(cluster.getMasterContainer().isRunning()).isTrue();
      assertThat(cluster.getReplicaContainers()).hasSize(1);
      assertThat(cluster.getSentinelContainers()).hasSize(1);
    }

    @Test
    @DisplayName("should expose master connection info")
    void shouldExposeMasterInfo(final SentinelCluster cluster) {
      assertThat(cluster.getMasterHost()).isNotBlank();
      assertThat(cluster.getMasterPort()).isGreaterThan(0);
    }

    @Test
    @DisplayName("should expose sentinel connection info")
    void shouldExposeSentinelInfo(final SentinelCluster cluster) {
      assertThat(cluster.getSentinels()).isNotEmpty();
      assertThat(cluster.getSentinels().get(0).getHost()).isNotBlank();
      assertThat(cluster.getSentinels().get(0).getPort()).isGreaterThan(0);
    }

    @Test
    @DisplayName("should expose replica connection info")
    void shouldExposeReplicaInfo(final SentinelCluster cluster) {
      assertThat(cluster.getReplicas()).hasSize(1);
      assertThat(cluster.getReplicas().get(0).getHost()).isNotBlank();
    }
  }

  @Nested
  @DisplayName("SentinelRedis parameter injection")
  class SentinelRedisParameterTests {

    @Test
    @DisplayName("should inject SentinelRedis parameter")
    void shouldInjectSentinelRedis(final SentinelRedis redis) {
      assertThat(redis).isNotNull();
      assertThat(redis.host()).isNotBlank();
      assertThat(redis.port()).isGreaterThan(0);
      assertThat(redis.masterName()).isEqualTo("mymaster");
      assertThat(redis.sentinels()).isNotEmpty();
      assertThat(redis.replicas()).hasSize(1);
    }

    @Test
    @DisplayName("should inject List<SentinelRedis> parameter")
    void shouldInjectSentinelRedisList(final List<SentinelRedis> clusters) {
      assertThat(clusters).hasSize(1);
      assertThat(clusters.get(0).masterName()).isEqualTo("mymaster");
    }
  }

  @Nested
  @DisplayName("INSTANCE programmatic access")
  class InstanceAccessTests {

    @Test
    @DisplayName("RedisSentinel.INSTANCE.get(id) should return cluster info")
    void shouldAccessViaInstance() {
      final SentinelRedis redis = RedisSentinel.INSTANCE.get("primary");
      assertThat(redis).isNotNull();
      assertThat(redis.host()).isNotBlank();
      assertThat(redis.masterName()).isEqualTo("mymaster");
    }

    @Test
    @DisplayName("RedisSentinel.INSTANCE.getAll() should return all clusters")
    void shouldGetAllViaInstance() {
      final List<SentinelRedis> all = RedisSentinel.INSTANCE.getAll();
      assertThat(all).hasSize(1);
    }
  }

  @Nested
  @DisplayName("SentinelCluster.toSentinelRedis()")
  class ToSentinelRedisTests {

    @Test
    @DisplayName("should convert cluster to SentinelRedis record")
    void shouldConvertToSentinelRedis(final SentinelCluster cluster) {
      final SentinelRedis redis = cluster.toSentinelRedis();
      assertThat(redis.host()).isEqualTo(cluster.getMasterHost());
      assertThat(redis.port()).isEqualTo(cluster.getMasterPort());
      assertThat(redis.masterName()).isEqualTo(cluster.getMasterName());
      assertThat(redis.sentinels()).hasSize(cluster.getSentinelContainers().size());
      assertThat(redis.replicas()).hasSize(cluster.getReplicaContainers().size());
    }
  }

  @Nested
  @DisplayName("ControlFacade via SentinelCluster")
  class ControlFacadeTests {

    @Test
    @DisplayName("getControl() should return non-null ControlFacade")
    void shouldReturnControlFacade(final SentinelCluster cluster) {
      assertThat(cluster.getControl()).isNotNull();
    }

    @Test
    @DisplayName("getControl() should be idempotent (lazy init)")
    void shouldBeLazyAndIdempotent(final SentinelCluster cluster) {
      final var first = cluster.getControl();
      final var second = cluster.getControl();
      assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("getMaster() via ControlFacade should return running container")
    void shouldReturnMasterContainer(final SentinelCluster cluster) {
      assertThat(cluster.getControl().getMaster().isRunning()).isTrue();
    }

    @Test
    @DisplayName("network() should return non-null NetworkChaosController")
    void shouldReturnNetwork(final SentinelCluster cluster) {
      assertThat(cluster.getControl().network()).isNotNull();
    }

    @Test
    @DisplayName("clearRoleCache() should not throw")
    void shouldClearCacheWithoutThrowing(final SentinelCluster cluster) {
      assertThatCode(() -> cluster.getControl().clearRoleCache()).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("Sentinel URIs")
  class SentinelUriTests {

    @Test
    @DisplayName("getMasterURI() should return valid Lettuce RedisURI")
    void shouldReturnMasterUri(final SentinelCluster cluster) {
      assertThat(cluster.getMasterURI()).isNotNull();
      assertThat(cluster.getMasterURI().getHost()).isEqualTo(cluster.getMasterHost());
      assertThat(cluster.getMasterURI().getPort()).isEqualTo(cluster.getMasterPort());
    }

    @Test
    @DisplayName("getSentinelURIs() should return URI for each sentinel")
    void shouldReturnSentinelUris(final SentinelCluster cluster) {
      assertThat(cluster.getSentinelURIs()).hasSize(cluster.getSentinelContainers().size());
    }
  }
}
