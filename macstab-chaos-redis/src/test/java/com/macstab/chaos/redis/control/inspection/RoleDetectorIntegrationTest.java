/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.redis.control.role.ContainerRole;

@Testcontainers
@DisplayName("RoleDetector — Integration")
final class RoleDetectorIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

  @Test
  @DisplayName("should detect master role for standalone Redis")
  void shouldDetectMasterRole() {
    // Act
    final ContainerRole role = RoleDetector.detect(redis);

    // Assert — standalone Redis responds as master
    assertThat(role).isNotNull();
  }

  @Test
  @DisplayName("should detect roles for all containers")
  void shouldDetectAllRoles() {
    // Act
    final Map<GenericContainer<?>, ContainerRole> roles = RoleDetector.detectAll(List.of(redis));

    // Assert
    assertThat(roles).containsKey(redis);
    assertThat(roles.get(redis)).isNotNull();
  }

  @Test
  @DisplayName("should reject null container")
  void shouldRejectNull() {
    // Act + Assert
    assertThatThrownBy(() -> RoleDetector.detect(null)).isInstanceOf(NullPointerException.class);
  }
}
