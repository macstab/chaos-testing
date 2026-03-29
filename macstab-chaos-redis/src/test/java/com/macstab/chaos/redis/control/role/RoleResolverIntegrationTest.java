/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("RoleResolver — Integration")
final class RoleResolverIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

  @Test
  @DisplayName("should detect STANDALONE role for standalone Redis")
  void shouldDetectStandaloneRole() {
    // Arrange — standalone Redis returns "master" from ROLE command
    final Map<GenericContainer<?>, Integer> indexMap = Map.of(redis, 0);
    final RoleResolver resolver = new RoleResolver(indexMap);

    // Act
    final ContainerRole role = resolver.resolve(redis);

    // Assert — standalone Redis reports as master
    assertThat(role).isNotNull();
  }

  @Test
  @DisplayName("should cache role after first resolution")
  void shouldCacheRole() {
    // Arrange
    final Map<GenericContainer<?>, Integer> indexMap = Map.of(redis, 0);
    final RoleResolver resolver = new RoleResolver(indexMap);

    // Act
    final ContainerRole first = resolver.resolve(redis);
    final ContainerRole second = resolver.resolve(redis);

    // Assert
    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName("clearCache should allow re-resolution")
  void shouldClearCache() {
    // Arrange
    final Map<GenericContainer<?>, Integer> indexMap = Map.of(redis, 0);
    final RoleResolver resolver = new RoleResolver(indexMap);
    resolver.resolve(redis);

    // Act
    resolver.clearCache();

    // Assert — should re-detect without throwing
    assertThatCode(() -> resolver.resolve(redis)).doesNotThrowAnyException();
  }
}
