/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("StandaloneContainerFactory — Integration")
final class StandaloneContainerFactoryIntegrationTest {

  @Test
  @DisplayName("should create and start standalone Redis")
  void shouldCreateAndStartStandalone() {
    // Arrange
    final GenericContainer<?> container = StandaloneContainerFactory.createStandalone();

    // Act + Assert
    try {
      container.start();
      assertThat(container.isRunning()).isTrue();
      assertThat(container.getMappedPort(6379)).isGreaterThan(0);
    } finally {
      container.stop();
    }
  }
}
