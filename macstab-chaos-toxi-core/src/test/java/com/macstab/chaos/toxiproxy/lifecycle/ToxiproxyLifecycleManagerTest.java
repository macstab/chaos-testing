/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.lifecycle;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;

/**
 * Comprehensive tests for {@link ToxiproxyLifecycleManager}.
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
      // GIVEN
      final ContainerContext ctx = ContainerContext.of(UBUNTU);

      // WHEN
      lifecycle.ensureRunning(ctx);

      // THEN
      assertThat(lifecycle.isHealthy(ctx)).isTrue();
    }

    @Test
    @DisplayName("should be idempotent — calling twice is a no-op")
    void shouldBeIdempotent() throws Exception {
      final ContainerContext ctx = ContainerContext.of(UBUNTU);

      lifecycle.ensureRunning(ctx);
      lifecycle.ensureRunning(ctx);

      assertThat(lifecycle.isHealthy(ctx)).isTrue();
    }

    @Test
    @DisplayName("should throw NullPointerException when ctx is null")
    void shouldThrowNpe_whenCtxIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> lifecycle.ensureRunning(null))
          .withMessage("ctx must not be null");
    }

    @Test
    @DisplayName("should throw IllegalStateException when container is not running")
    void shouldThrow_whenContainerNotRunning() {
      // GIVEN — a ctx whose container reports isRunning() = false
      // Use mock to control isRunning() without starting a real container
      final GenericContainer<?> stopped = org.mockito.Mockito.mock(GenericContainer.class);
      org.mockito.Mockito.when(stopped.isRunning()).thenReturn(false);
      final com.macstab.chaos.core.platform.Platform platform =
          org.mockito.Mockito.mock(com.macstab.chaos.core.platform.Platform.class);
      final com.macstab.chaos.core.shell.Shell shell =
          org.mockito.Mockito.mock(com.macstab.chaos.core.shell.Shell.class);
      final ContainerContext ctx = ContainerContext.of(stopped, platform, shell);

      // WHEN / THEN
      assertThatThrownBy(() -> lifecycle.ensureRunning(ctx))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be running");
    }
  }

  @Nested
  @DisplayName("stop()")
  class StopTests {

    @Test
    @DisplayName("should stop Toxiproxy and become unhealthy")
    void shouldStopToxiproxy() throws Exception {
      // GIVEN
      final ContainerContext ctx = ContainerContext.of(UBUNTU);
      lifecycle.ensureRunning(ctx);
      assertThat(lifecycle.isHealthy(ctx)).isTrue();

      // WHEN
      lifecycle.shutdown(ctx);

      // THEN
      assertThat(lifecycle.isHealthy(ctx)).isFalse();
    }

    @Test
    @DisplayName("should handle stop when never started")
    void shouldHandleStopWhenNeverStarted() throws Exception {
      final ContainerContext ctx = ContainerContext.of(UBUNTU);
      assertThatNoException().isThrownBy(() -> lifecycle.shutdown(ctx));
    }

    @Test
    @DisplayName("should throw NullPointerException when ctx is null")
    void shouldThrowNpe_whenCtxIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> lifecycle.shutdown(null))
          .withMessage("ctx must not be null");
    }
  }

  @Nested
  @DisplayName("isHealthy()")
  class IsHealthyTests {

    @Test
    @DisplayName("should return false when not started")
    void shouldReturnFalse_whenNotStarted() {
      final ContainerContext ctx = ContainerContext.of(UBUNTU);
      assertThat(lifecycle.isHealthy(ctx)).isFalse();
    }

    @Test
    @DisplayName("should return true when running")
    void shouldReturnTrue_whenRunning() throws Exception {
      final ContainerContext ctx = ContainerContext.of(UBUNTU);
      lifecycle.ensureRunning(ctx);
      assertThat(lifecycle.isHealthy(ctx)).isTrue();
    }

    @Test
    @DisplayName("should return false after stop")
    void shouldReturnFalse_afterStop() throws Exception {
      final ContainerContext ctx = ContainerContext.of(UBUNTU);
      lifecycle.ensureRunning(ctx);
      lifecycle.shutdown(ctx);
      assertThat(lifecycle.isHealthy(ctx)).isFalse();
    }

    @Test
    @DisplayName("should throw NullPointerException when ctx is null")
    void shouldThrowNpe_whenCtxIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> lifecycle.isHealthy(null))
          .withMessage("ctx must not be null");
    }
  }

  @Nested
  @DisplayName("Lifecycle cycles")
  class LifecycleCycleTests {

    @Test
    @DisplayName("should support multiple start/stop cycles")
    void shouldSupportMultipleCycles() throws Exception {
      // GIVEN
      final ContainerContext ctx = ContainerContext.of(UBUNTU);

      // Cycle 1
      lifecycle.ensureRunning(ctx);
      assertThat(lifecycle.isHealthy(ctx)).isTrue();
      lifecycle.shutdown(ctx);
      assertThat(lifecycle.isHealthy(ctx)).isFalse();

      // Cycle 2
      lifecycle.ensureRunning(ctx);
      assertThat(lifecycle.isHealthy(ctx)).isTrue();
      lifecycle.shutdown(ctx);
      assertThat(lifecycle.isHealthy(ctx)).isFalse();
    }
  }

  @Nested
  @DisplayName("Configuration")
  class ConfigurationTests {

    @Test
    @DisplayName("should accept custom configuration")
    void shouldAcceptCustomConfig() {
      final ToxiproxyConfig customConfig =
          ToxiproxyConfig.builder()
              .apiUrl("http://localhost:8474")
              .startupTimeoutMs(10000)
              .pollIntervalMs(100)
              .build();

      assertThatCode(() -> new ToxiproxyLifecycleManager(customConfig)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should throw NullPointerException on null config")
    void shouldThrowNpe_onNullConfig() {
      assertThatNullPointerException()
          .isThrownBy(() -> new ToxiproxyLifecycleManager(null))
          .withMessage("config must not be null");
    }
  }
}
