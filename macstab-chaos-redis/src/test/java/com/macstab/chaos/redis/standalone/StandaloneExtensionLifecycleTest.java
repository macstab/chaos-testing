/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.api.RedisTopology;
import com.macstab.chaos.redis.api.StandaloneRedis;

/**
 * Exercises the full {@code @RedisStandalone} extension lifecycle.
 *
 * <p>Running this test class covers:
 *
 * <ul>
 *   <li>{@code RedisContainerExtension.beforeAll/afterAll} — JUnit 5 lifecycle callbacks
 *   <li>{@code StandaloneStartupOrchestrator.start()} — parallel container startup
 *   <li>{@code RedisContainerExtension.getContainer(id)} — programmatic access
 *   <li>Parameter injection: {@code StandaloneRedis}, {@code RedisConnectionInfo}, {@code
 *       List<StandaloneRedis>}
 *   <li>{@code RedisStandalone.INSTANCE.get(id)} — via {@code ChaosContainers}
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@RedisStandalone(id = "primary", version = "7.4")
@DisplayName("RedisContainerExtension — Standalone Lifecycle Integration")
class StandaloneExtensionLifecycleTest {

  @Nested
  @DisplayName("StandaloneRedis parameter injection")
  class StandaloneRedisInjectionTests {

    @Test
    @DisplayName("should inject StandaloneRedis parameter")
    void shouldInjectStandaloneRedis(final StandaloneRedis redis) {
      assertThat(redis).isNotNull();
      assertThat(redis.host()).isNotBlank();
      assertThat(redis.port()).isGreaterThan(0);
      assertThat(redis.getTopology()).isEqualTo(RedisTopology.STANDALONE);
    }

    @Test
    @DisplayName("should inject List<StandaloneRedis> parameter (single element)")
    void shouldInjectList(final List<StandaloneRedis> all) {
      assertThat(all).hasSize(1);
      assertThat(all.get(0).host()).isNotBlank();
    }
  }

  @Nested
  @DisplayName("INSTANCE programmatic access")
  class InstanceAccessTests {

    @Test
    @DisplayName("RedisStandalone.INSTANCE.get(id) should return connection info")
    void shouldAccessViaInstance() {
      final StandaloneRedis redis = RedisStandalone.INSTANCE.get("primary");
      assertThat(redis).isNotNull();
      assertThat(redis.host()).isNotBlank();
      assertThat(redis.port()).isGreaterThan(0);
    }

    @Test
    @DisplayName("RedisStandalone.INSTANCE.getAll() should return all instances")
    void shouldGetAllViaInstance() {
      final List<StandaloneRedis> all = RedisStandalone.INSTANCE.getAll();
      assertThat(all).hasSize(1);
    }
  }

  @Nested
  @DisplayName("Container is actually running Redis")
  class ContainerHealthTests {

    @Test
    @DisplayName("should accept connections on mapped port")
    void shouldAcceptConnections(final StandaloneRedis redis) throws Exception {
      // Verify Redis responds — use Lettuce for clean connection test
      final var uri =
          io.lettuce.core.RedisURI.builder().withHost(redis.host()).withPort(redis.port()).build();
      final var client = io.lettuce.core.RedisClient.create(uri);
      try (final var conn = client.connect()) {
        final var pong = conn.sync().ping();
        assertThat(pong).isEqualToIgnoringCase("PONG");
      } finally {
        client.shutdown();
      }
    }

    @Test
    @DisplayName("should support basic SET and GET")
    void shouldSupportSetAndGet(final StandaloneRedis redis) throws Exception {
      final var uri =
          io.lettuce.core.RedisURI.builder().withHost(redis.host()).withPort(redis.port()).build();
      final var client = io.lettuce.core.RedisClient.create(uri);
      try (final var conn = client.connect()) {
        final var cmd = conn.sync();
        cmd.set("lifecycle-test-key", "value");
        assertThat(cmd.get("lifecycle-test-key")).isEqualTo("value");
        cmd.del("lifecycle-test-key");
      } finally {
        client.shutdown();
      }
    }
  }
}
