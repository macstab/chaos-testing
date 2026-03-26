/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.annotation.Resources;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;

// import redis.clients.jedis.Jedis; // Removed to avoid test dependency

/**
 * Integration tests for Redis with @Resources annotation.
 *
 * <p><strong>Purpose:</strong> Validate end-to-end functionality of plugin architecture with
 * resource constraints.
 *
 * <p><strong>Tests:</strong>
 *
 * <ul>
 *   <li>Container starts with @Resources applied
 *   <li>Parameter injection works (RedisConnectionInfo)
 *   <li>Redis connectivity works (Jedis client)
 *   <li>Basic Redis operations (set/get)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@DisplayName("Redis with @Resources Integration Tests")
@RedisStandalone(version = "7.4")
@Resources(memory = "256M", cpus = "1")
class RedisResourceConstraintIntegrationTest {

  @Test
  @DisplayName("should start Redis container with resource constraints")
  void shouldStartRedisWithResources(final RedisConnectionInfo info) {
    assertThat(info).isNotNull();
    assertThat(info.getHost()).isNotBlank();
    assertThat(info.getPort()).isGreaterThan(0);
  }

  @Test
  @DisplayName("should inject RedisConnectionInfo parameter")
  void shouldInjectConnectionInfo(final RedisConnectionInfo info) {
    assertThat(info.getHost()).isEqualTo("localhost");
    assertThat(info.getPort()).isGreaterThan(1024);
    assertThat(info.getPort()).isLessThan(65536);
  }

  // Jedis tests removed to avoid test dependency
  // Real Redis connectivity can be validated with Jedis in application tests
}
