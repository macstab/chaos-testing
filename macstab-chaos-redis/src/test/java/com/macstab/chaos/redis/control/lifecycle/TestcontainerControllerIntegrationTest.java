/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("TestcontainerController — Integration")
final class TestcontainerControllerIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

  private final TestcontainerController controller = new TestcontainerController();

  @Nested
  @DisplayName("waitForReady")
  class WaitForReadyTests {

    @Test
    @DisplayName("should succeed for running container")
    void shouldSucceedForRunning() {
      // Arrange + Act + Assert — no exception means success
      controller.waitForReady(redis);
    }

    @Test
    @DisplayName("should succeed with custom timeout")
    void shouldSucceedWithCustomTimeout() {
      // Arrange
      final Duration timeout = Duration.ofSeconds(10);
      // Act + Assert — no exception means success
      controller.waitForReady(redis, timeout);
    }

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNull() {
      // Arrange + Act + Assert
      assertThatThrownBy(() -> controller.waitForReady(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("restart")
  class RestartTests {

    @Test
    @DisplayName("should restart container successfully")
    void shouldRestartContainer() {
      // Act
      controller.restart(redis);
      // Assert
      assertThat(redis.isRunning()).isTrue();
    }
  }

  @Nested
  @DisplayName("pause / resume")
  class PauseResumeTests {

    @Test
    @DisplayName("should pause and resume container")
    void shouldPauseAndResume() {
      // Act
      controller.pause(redis);
      controller.resume(redis);
      // Assert
      assertThat(redis.isRunning()).isTrue();
    }
  }
}
