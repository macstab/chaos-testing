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
  @DisplayName("ensureRunning()")
  class EnsureRunningTests {

    @Test
    @DisplayName("should start Toxiproxy successfully")
    void shouldStartToxiproxy() throws Exception {
      lifecycle.ensureRunning(UBUNTU);
      assertThat(lifecycle.isHealthy(UBUNTU)).isTrue();
    }

    @Test
    @DisplayName("should be idempotent (calling again is no-op)")
    void shouldBeIdempotent() throws Exception {
      lifecycle.ensureRunning(UBUNTU);
      lifecycle.ensureRunning(UBUNTU); // Second call - no-op
      assertThat(lifecycle.isHealthy(UBUNTU)).isTrue();
    }

    @Test
    @DisplayName("should fail on null container")
    void shouldFailOnNullContainer() {
      assertThatThrownBy(() -> lifecycle.ensureRunning(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should fail if container is not running")
    void shouldFailIfContainerNotRunning() {
      // Use a stopped container to trigger IllegalStateException
      @SuppressWarnings("resource")
      GenericContainer<?> stopped = new GenericContainer<>("alpine:latest");
      // Not started — container.isRunning() returns false

      assertThatThrownBy(() -> lifecycle.ensureRunning(stopped))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }
  }

  @Nested
  @DisplayName("stop()")
  class StopTests {

    @Test
    @DisplayName("should stop running Toxiproxy")
    void shouldStopToxiproxy() throws Exception {
      lifecycle.ensureRunning(UBUNTU);
      assertThat(lifecycle.isHealthy(UBUNTU)).isTrue();

      lifecycle.stop(UBUNTU);

      assertThat(lifecycle.isHealthy(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should be idempotent (stopping when not running is no-op)")
    void shouldBeIdempotent() throws Exception {
      lifecycle.ensureRunning(UBUNTU);
      lifecycle.stop(UBUNTU);
      lifecycle.stop(UBUNTU); // Stop again - no exception
      assertThat(lifecycle.isHealthy(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should handle stop when never started")
    void shouldHandleStopWhenNeverStarted() {
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
  @DisplayName("isHealthy()")
  class IsHealthyTests {

    @Test
    @DisplayName("should return false when not started")
    void shouldReturnFalseWhenNotStarted() {
      assertThat(lifecycle.isHealthy(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should return true when running")
    void shouldReturnTrueWhenRunning() throws Exception {
      lifecycle.ensureRunning(UBUNTU);
      assertThat(lifecycle.isHealthy(UBUNTU)).isTrue();
    }

    @Test
    @DisplayName("should return false after stop")
    void shouldReturnFalseAfterStop() throws Exception {
      lifecycle.ensureRunning(UBUNTU);
      lifecycle.stop(UBUNTU);
      assertThat(lifecycle.isHealthy(UBUNTU)).isFalse();
    }
  }

  @Nested
  @DisplayName("Lifecycle Scenarios")
  class LifecycleScenarios {

    @Test
    @DisplayName("should support multiple start/stop cycles")
    void shouldSupportMultipleCycles() throws Exception {
      lifecycle.ensureRunning(UBUNTU);
      assertThat(lifecycle.isHealthy(UBUNTU)).isTrue();
      lifecycle.stop(UBUNTU);
      assertThat(lifecycle.isHealthy(UBUNTU)).isFalse();

      lifecycle.ensureRunning(UBUNTU);
      assertThat(lifecycle.isHealthy(UBUNTU)).isTrue();
      lifecycle.stop(UBUNTU);
      assertThat(lifecycle.isHealthy(UBUNTU)).isFalse();
    }

    @Test
    @DisplayName("should handle cleanup after multiple operations")
    void shouldCleanupAfterMultipleOperations() throws Exception {
      lifecycle.ensureRunning(UBUNTU);
      lifecycle.stop(UBUNTU);
      lifecycle.ensureRunning(UBUNTU);
      lifecycle.stop(UBUNTU);
      assertThat(lifecycle.isHealthy(UBUNTU)).isFalse();
    }
  }

  @Nested
  @DisplayName("Configuration")
  class ConfigurationTests {

    @Test
    @DisplayName("should use custom config from builder")
    void shouldUseCustomConfig() {
      ToxiproxyConfig customConfig = ToxiproxyConfig.builder()
          .apiUrl("http://localhost:8474")
          .startupTimeoutMs(10000)
          .pollIntervalMs(100)
          .build();

      // Should instantiate without error
      assertThatCode(() -> new ToxiproxyLifecycleManager(customConfig))
          .doesNotThrowAnyException();
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
