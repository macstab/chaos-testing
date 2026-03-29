/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("ControlFacade — Integration")
final class ControlFacadeIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

  private ControlFacade facade;

  @BeforeEach
  void setUp() {
    final Map<GenericContainer<?>, Integer> indexMap = Map.of(redis, 0);
    facade = ControlFacade.create(List.of(redis), indexMap);
  }

  @Test
  @DisplayName("should restart container")
  void shouldRestart() {
    // Act
    facade.restart(redis);
    // Assert
    assertThat(redis.isRunning()).isTrue();
  }

  @Test
  @DisplayName("should wait for ready")
  void shouldWaitForReady() {
    // Act + Assert
    assertThatCode(() -> facade.waitForReady(redis)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("network() should return non-null controller")
  void shouldReturnNetwork() {
    // Act + Assert
    assertThat(facade.network()).isNotNull();
  }

  @Test
  @DisplayName("clearRoleCache() should not throw")
  void shouldClearCache() {
    // Act + Assert
    assertThatCode(() -> facade.clearRoleCache()).doesNotThrowAnyException();
  }
}
