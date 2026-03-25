/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.lifecycle;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.macstab.chaos.proxy.config.ToxiproxyConfig;

/**
 * Comprehensive tests for ToxiproxyLifecycleManager.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("ToxiproxyLifecycleManager")
class ToxiproxyLifecycleManagerTest {

  @Container
  private static final GenericContainer<?> UBUNTU =
      new GenericContainer<>("ubuntu:22.04").withCommand("sleep", "infinity");

  private final ToxiproxyConfig config = ToxiproxyConfig.defaults();
  private final ToxiproxyLifecycleManager lifecycle = new ToxiproxyLifecycleManager(config);

  @Nested
  @DisplayName("start()")
  class StartTests {

    @Test
    @DisplayName("should start Toxiproxy successfully")
    void shouldStartToxiproxy() {
      // When
      lifecycle.start(UBUNTU);

      // Then
      assertThat(lifecycle.isRunning(UBUNTU)).isTrue();
    }

    @Test
    @DisplayName("should be idempotent (starting again is no-op)")
    void shouldBeIdempotent() {
      // Given
      lifecycle.start(UBUNTU);

      // When
      lifecycle.start(UBUNTU); // Start again

      // Then
      assertThat(lifecycle.isRunning(UBUNTU)).isTrue();
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> lifecycle.start(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should timeout if Toxiproxy doesn't start")
    void shouldTimeoutIfNotStarting() {
      // Create config with very short timeout
      ToxiproxyConfig shortTimeout =
          new ToxiproxyConfig(
              "http://localhost:8474",
              100, // 100ms startup timeout (too short)
              100,
              2000,
              5000,
              5000);

      ToxiproxyLifecycleManager manager = new ToxiproxyLifecycleManager(shortTimeout);

      // Should timeout (Toxiproxy needs ~500ms to start)
      assertThatThrownBy(() -> manager.start(UBUNTU))
          .hasMessageContaining("timeout")
          .hasMessageContaining("100");
    }
  }

  @Nested
  @DisplayName("stop()")
  class StopTests {

    @Test
    @DisplayName("should stop running Toxiproxy")
    void shouldStopToxiproxy() {
      // Given
      lifecycle.start(UBUNTU);
      assertThat(lifecycle.isRunning(UBUNTU)).isTrue();

      // When
      lifecycle.stop(UBUNTU);

      // Then
      assertThat(lifecycle.isRunning(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should be idempotent (stopping when not running is no-op)")
    void shouldBeIdempotent() {
      // Given
      lifecycle.start(UBUNTU);
      lifecycle.stop(UBUNTU);

      // When
      lifecycle.stop(UBUNTU); // Stop again

      // Then - no exception
      assertThat(lifecycle.isRunning(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should handle stop when never started")
    void shouldHandleStopWhenNeverStarted() {
      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> lifecycle.stop(UBUNTU));
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> lifecycle.stop(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }
  }

  @Nested
  @DisplayName("isRunning()")
  class IsRunningTests {

    @Test
    @DisplayName("should return false when not started")
    void shouldReturnFalseWhenNotStarted() {
      assertThat(lifecycle.isRunning(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should return true when running")
    void shouldReturnTrueWhenRunning() {
      lifecycle.start(UBUNTU);
      assertThat(lifecycle.isRunning(UBUNTU)).isTrue();
    }

    @Test
    @DisplayName("should return false after stop")
    void shouldReturnFalseAfterStop() {
      lifecycle.start(UBUNTU);
      lifecycle.stop(UBUNTU);
      assertThat(lifecycle.isRunning(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> lifecycle.isRunning(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }
  }

  @Nested
  @DisplayName("reset()")
  class ResetTests {

    @Test
    @DisplayName("should stop Toxiproxy and cleanup")
    void shouldStopAndCleanup() {
      // Given
      lifecycle.start(UBUNTU);

      // When
      lifecycle.reset(UBUNTU);

      // Then
      assertThat(lifecycle.isRunning(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should be idempotent")
    void shouldBeIdempotent() {
      lifecycle.start(UBUNTU);
      lifecycle.reset(UBUNTU);

      // When/Then - no exception
      assertThatNoException().isThrownBy(() -> lifecycle.reset(UBUNTU));
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> lifecycle.reset(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }
  }

  @Nested
  @DisplayName("Lifecycle Scenarios")
  class LifecycleScenarios {

    @Test
    @DisplayName("should support multiple start/stop cycles")
    void shouldSupportMultipleCycles() {
      // Cycle 1
      lifecycle.start(UBUNTU);
      assertThat(lifecycle.isRunning(UBUNTU)).isTrue();
      lifecycle.stop(UBUNTU);
      assertThat(lifecycle.isRunning(UBUNTU)).isFalse();

      // Cycle 2
      lifecycle.start(UBUNTU);
      assertThat(lifecycle.isRunning(UBUNTU)).isTrue();
      lifecycle.stop(UBUNTU);
      assertThat(lifecycle.isRunning(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should handle rapid start/stop")
    void shouldHandleRapidStartStop() {
      for (int i = 0; i < 5; i++) {
        lifecycle.start(UBUNTU);
        lifecycle.stop(UBUNTU);
      }

      assertThat(lifecycle.isRunning(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should cleanup on reset after multiple operations")
    void shouldCleanupAfterMultipleOperations() {
      lifecycle.start(UBUNTU);
      lifecycle.stop(UBUNTU);
      lifecycle.start(UBUNTU);
      lifecycle.reset(UBUNTU);

      assertThat(lifecycle.isRunning(UBUNTU)).isFalse();
    }
  }

  @Nested
  @DisplayName("Configuration")
  class ConfigurationTests {

    @Test
    @DisplayName("should use custom startup timeout")
    void shouldUseCustomStartupTimeout() {
      ToxiproxyConfig customConfig =
          new ToxiproxyConfig(
              "http://localhost:8474",
              5, // Very short timeout
              100,
              2000,
              5000,
              5000);

      ToxiproxyLifecycleManager manager = new ToxiproxyLifecycleManager(customConfig);

      // Should timeout with custom value
      assertThatThrownBy(() -> manager.start(UBUNTU))
          .hasMessageContaining("5"); // Custom timeout value
    }

    @Test
    @DisplayName("should fail on null config")
    void shouldFailOnNullConfig() {
      assertThatThrownBy(() -> new ToxiproxyLifecycleManager(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config");
    }
  }
}
