/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.redis.control.role.RoleResolver;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

@Testcontainers
@DisplayName("LettuceConnectionInspector — Integration")
final class LettuceConnectionInspectorIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

  @Test
  @DisplayName("should inspect connection with explicit container hint")
  void shouldInspectWithHint() {
    // Arrange
    final Map<GenericContainer<?>, Integer> indexMap = Map.of(redis, 0);
    final RoleResolver resolver = new RoleResolver(indexMap);
    final LettuceConnectionInspector inspector =
        new LettuceConnectionInspector(List.of(redis), resolver);

    final RedisClient client =
        RedisClient.create(
            RedisURI.builder()
                .withHost(redis.getHost())
                .withPort(redis.getFirstMappedPort())
                .build());

    // Act + Assert
    try (final StatefulRedisConnection<String, String> conn = client.connect()) {
      final ConnectionInfo info = inspector.inspect(conn, redis);
      assertThat(info).isNotNull();
      assertThat(info.container()).isEqualTo(redis);
    } finally {
      client.shutdown();
    }
  }

  @Test
  @DisplayName("should inspect connection manually")
  void shouldInspectManually() {
    // Arrange
    final Map<GenericContainer<?>, Integer> indexMap = Map.of(redis, 0);
    final RoleResolver resolver = new RoleResolver(indexMap);
    final LettuceConnectionInspector inspector =
        new LettuceConnectionInspector(List.of(redis), resolver);

    final RedisClient client =
        RedisClient.create(
            RedisURI.builder()
                .withHost(redis.getHost())
                .withPort(redis.getFirstMappedPort())
                .build());

    // Act + Assert
    try (final StatefulRedisConnection<String, String> conn = client.connect()) {
      final ConnectionInfo info = inspector.inspectManual(redis, "test connection");
      assertThat(info).isNotNull();
      assertThat(info.container()).isEqualTo(redis);
    } finally {
      client.shutdown();
    }
  }
}
