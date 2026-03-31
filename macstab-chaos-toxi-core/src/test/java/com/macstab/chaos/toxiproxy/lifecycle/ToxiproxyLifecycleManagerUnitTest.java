/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.lifecycle;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;

/**
 * Unit tests for {@link ToxiproxyLifecycleManager} covering branches unreachable via integration
 * tests: 3-arg constructor null guards, container-not-running guards, exception catch paths, and
 * startup timeout.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToxiproxyLifecycleManager — unit")
class ToxiproxyLifecycleManagerUnitTest {

  @Mock private ToxiproxyInstaller mockInstaller;
  @Mock private ToxiproxyApiClient mockApiClient;
  @Mock private GenericContainer<?> mockContainer;
  @Mock private Platform mockPlatform;
  @Mock private Shell mockShell;

  private ToxiproxyConfig defaultConfig;
  private ToxiproxyLifecycleManager lifecycle;
  private ContainerContext ctx;

  @BeforeEach
  void setUp() {
    defaultConfig = ToxiproxyConfig.defaults();
    lifecycle = new ToxiproxyLifecycleManager(defaultConfig, mockInstaller, mockApiClient);
    lenient().when(mockContainer.isRunning()).thenReturn(true);
    ctx = ContainerContext.of(mockContainer, mockPlatform, mockShell);
  }

  // ==================== Constructor (3-arg) ====================

  @Nested
  @DisplayName("Constructor (3-arg) — null guards")
  class ConstructorNullGuardTests {

    @Test
    @DisplayName("null installer throws NullPointerException")
    void nullInstaller_throws() {
      assertThatNullPointerException()
          .isThrownBy(() -> new ToxiproxyLifecycleManager(defaultConfig, null, mockApiClient))
          .withMessage("installer must not be null");
    }

    @Test
    @DisplayName("null apiClient throws NullPointerException")
    void nullApiClient_throws() {
      assertThatNullPointerException()
          .isThrownBy(() -> new ToxiproxyLifecycleManager(defaultConfig, mockInstaller, null))
          .withMessage("apiClient must not be null");
    }
  }

  // ==================== ensureRunning() ====================

  @Nested
  @DisplayName("ensureRunning()")
  class EnsureRunningTests {

    @Test
    @DisplayName("API already ready — returns immediately without installing")
    void apiAlreadyReady_returnsImmediately() throws Exception {
      when(mockApiClient.isApiReady(ctx)).thenReturn(true);

      lifecycle.ensureRunning(ctx);

      verify(mockInstaller, never()).install(any());
    }

    @Test
    @DisplayName("startToxiproxyServer shell.exec throws — wraps in ChaosOperationFailedException")
    void shellExecThrows_wrapsChaosOperationFailed() throws Exception {
      when(mockApiClient.isApiReady(ctx)).thenReturn(false);
      when(mockShell.exec(any(), anyString()))
          .thenThrow(new RuntimeException("exec failed in container"));

      assertThatThrownBy(() -> lifecycle.ensureRunning(ctx))
          .isInstanceOf(com.macstab.chaos.core.exception.ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to start Toxiproxy");
    }

    @Test
    @DisplayName("API never becomes ready — throws ChaosOperationFailedException after timeout")
    void apiNeverReady_throwsAfterTimeout() throws Exception {
      final ToxiproxyConfig shortTimeout =
          ToxiproxyConfig.builder()
              .apiUrl("http://localhost:8474")
              .startupTimeoutMs(150)
              .pollIntervalMs(50)
              .build();
      final ToxiproxyLifecycleManager shortLifecycle =
          new ToxiproxyLifecycleManager(shortTimeout, mockInstaller, mockApiClient);

      // API never ready — triggers timeout in waitForApiReady()
      when(mockApiClient.isApiReady(ctx)).thenReturn(false);
      // shell.exec for startToxiproxyServer — no-op (result ignored by implementation)
      doNothing().when(mockInstaller).install(any());

      assertThatThrownBy(() -> shortLifecycle.ensureRunning(ctx))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("did not start within");
    }
  }

  // ==================== stop() ====================

  @Nested
  @DisplayName("stop()")
  class StopTests {

    @Test
    @DisplayName("container not running — throws IllegalStateException")
    void containerNotRunning_throwsIllegalState() {
      when(mockContainer.isRunning()).thenReturn(false);

      assertThatThrownBy(() -> lifecycle.stop(ctx))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be running");
    }
  }

  // ==================== isHealthy() ====================

  @Nested
  @DisplayName("isHealthy()")
  class IsHealthyTests {

    @Test
    @DisplayName("container not running — returns false without calling apiClient")
    void containerNotRunning_returnsFalse() {
      when(mockContainer.isRunning()).thenReturn(false);

      assertThat(lifecycle.isHealthy(ctx)).isFalse();
      verify(mockApiClient, never()).isApiReady(any());
    }

    @Test
    @DisplayName("apiClient.isApiReady throws — swallowed, returns false")
    void apiClientThrows_returnsFalse() {
      when(mockApiClient.isApiReady(ctx)).thenThrow(new RuntimeException("connection refused"));

      assertThat(lifecycle.isHealthy(ctx)).isFalse();
    }

    @Test
    @DisplayName("apiClient.isApiReady returns true — returns true")
    void apiClientReturnsTrue_returnsTrue() {
      when(mockApiClient.isApiReady(ctx)).thenReturn(true);

      assertThat(lifecycle.isHealthy(ctx)).isTrue();
    }

    @Test
    @DisplayName("apiClient.isApiReady returns false — returns false")
    void apiClientReturnsFalse_returnsFalse() {
      when(mockApiClient.isApiReady(ctx)).thenReturn(false);

      assertThat(lifecycle.isHealthy(ctx)).isFalse();
    }
  }
}
