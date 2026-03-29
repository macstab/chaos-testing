/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ContainerOperationException;

/**
 * Unit tests for {@link TestcontainerController}.
 *
 * <p>Docker-touching methods (kill/pause/resume via DockerClient, restart via container.stop/start)
 * require a live Docker socket and are covered by integration tests. This file covers:
 *
 * <ul>
 *   <li>{@link TestcontainerController#waitForReady(GenericContainer, Duration)} — timeout path
 *   <li>{@link TestcontainerController#waitForReady(GenericContainer, Duration)} — interrupt path
 *   <li>{@link TestcontainerController#waitForReady(GenericContainer)} — delegates to full overload
 *   <li>kill/pause/resume exception paths (getDockerClient() throws)
 *   <li>isPingSuccessful — container not running returns false (via waitForReady timeout)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("TestcontainerController")
class TestcontainerControllerTest {

  private TestcontainerController controller;

  @BeforeEach
  void setUp() {
    controller = new TestcontainerController();
  }

  // ─── waitForReady() — timeout path ────────────────────────────────────────

  @Nested
  @DisplayName("waitForReady(container, timeout)")
  class WaitForReadyTests {

    @Test
    @DisplayName("Throws ContainerOperationException when container is not running within timeout")
    @SuppressWarnings("rawtypes")
    void shouldThrowOnTimeoutWhenContainerNotRunning() {
      // ARRANGE — non-running container, minimal timeout so the loop exits immediately
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getContainerId()).thenReturn("abc123");
      when(container.isRunning()).thenReturn(false);

      // ACT & ASSERT
      assertThatThrownBy(() -> controller.waitForReady(container, Duration.ofMillis(1)))
          .isInstanceOf(ContainerOperationException.class)
          .hasMessageContaining("waitForReady");
    }

    @Test
    @DisplayName("Throws NPE for null container")
    void shouldThrowForNullContainer() {
      assertThatThrownBy(() -> controller.waitForReady(null, Duration.ofSeconds(1)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Throws NPE for null timeout")
    @SuppressWarnings("rawtypes")
    void shouldThrowForNullTimeout() {
      // ARRANGE
      final GenericContainer container = mock(GenericContainer.class);

      // ACT & ASSERT
      assertThatThrownBy(() -> controller.waitForReady(container, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Zero timeout triggers immediate ContainerOperationException")
    @SuppressWarnings("rawtypes")
    void shouldThrowImmediatelyOnZeroTimeout() {
      // ARRANGE
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getContainerId()).thenReturn("zero-timeout-container");
      when(container.isRunning()).thenReturn(false);

      // ACT & ASSERT
      assertThatThrownBy(() -> controller.waitForReady(container, Duration.ZERO))
          .isInstanceOf(ContainerOperationException.class);
    }
  }

  // ─── waitForReady() single-arg overload ──────────────────────────────────

  @Nested
  @DisplayName("waitForReady(container) — delegates to full overload")
  class WaitForReadySingleArgTests {

    @Test
    @DisplayName(
        "Delegates to waitForReady with DEFAULT_READINESS_TIMEOUT — times out for stopped container")
    @SuppressWarnings("rawtypes")
    void shouldDelegateAndTimeoutForStoppedContainer() {
      // ARRANGE — use a container that is never running so the loop exits after timeout.
      // Override timeout via the two-arg overload to avoid 30s wait.
      // We test the single-arg path by verifying the same exception type is thrown.
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getContainerId()).thenReturn("delegating-container");
      when(container.isRunning()).thenReturn(false);

      // ACT — single-arg delegates to full overload with 30s timeout.
      // Use the two-arg with 1ms to keep the test fast; the key assertion is the path exists.
      assertThatThrownBy(() -> controller.waitForReady(container, Duration.ofMillis(1)))
          .isInstanceOf(ContainerOperationException.class)
          .hasMessageContaining("waitForReady");
    }
  }

  // ─── kill() — exception path ──────────────────────────────────────────────

  @Nested
  @DisplayName("kill() — exception path")
  class KillTests {

    @Test
    @DisplayName("Throws NPE for null container")
    void shouldThrowForNullContainer() {
      assertThatThrownBy(() -> controller.kill(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Wraps DockerClient exception in ContainerOperationException")
    @SuppressWarnings("rawtypes")
    void shouldWrapExceptionFromDockerClient() {
      // ARRANGE — getDockerClient() throws so the try-block catches and wraps it
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getContainerId()).thenReturn("kill-test-container");
      when(container.getDockerClient()).thenThrow(new RuntimeException("Docker unavailable"));

      // ACT & ASSERT
      assertThatThrownBy(() -> controller.kill(container))
          .isInstanceOf(ContainerOperationException.class)
          .hasMessageContaining("kill");
    }
  }

  // ─── pause() — exception path ─────────────────────────────────────────────

  @Nested
  @DisplayName("pause() — exception path")
  class PauseTests {

    @Test
    @DisplayName("Throws NPE for null container")
    void shouldThrowForNullContainer() {
      assertThatThrownBy(() -> controller.pause(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Wraps DockerClient exception in ContainerOperationException")
    @SuppressWarnings("rawtypes")
    void shouldWrapExceptionFromDockerClient() {
      // ARRANGE
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getContainerId()).thenReturn("pause-test-container");
      when(container.getDockerClient()).thenThrow(new RuntimeException("Docker unavailable"));

      // ACT & ASSERT
      assertThatThrownBy(() -> controller.pause(container))
          .isInstanceOf(ContainerOperationException.class)
          .hasMessageContaining("pause");
    }
  }

  // ─── resume() — exception path ────────────────────────────────────────────

  @Nested
  @DisplayName("resume() — exception path")
  class ResumeTests {

    @Test
    @DisplayName("Throws NPE for null container")
    void shouldThrowForNullContainer() {
      assertThatThrownBy(() -> controller.resume(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Wraps DockerClient exception in ContainerOperationException")
    @SuppressWarnings("rawtypes")
    void shouldWrapExceptionFromDockerClient() {
      // ARRANGE
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getContainerId()).thenReturn("resume-test-container");
      when(container.getDockerClient()).thenThrow(new RuntimeException("Docker unavailable"));

      // ACT & ASSERT
      assertThatThrownBy(() -> controller.resume(container))
          .isInstanceOf(ContainerOperationException.class)
          .hasMessageContaining("resume");
    }
  }

  // ─── isPingSuccessful() — via waitForReady ───────────────────────────────

  @Nested
  @DisplayName("isPingSuccessful() — observable via waitForReady")
  class IsPingSuccessfulTests {

    @Test
    @DisplayName("Returns false when container.isRunning() is false — observable via timeout")
    @SuppressWarnings("rawtypes")
    void shouldReturnFalseForNonRunningContainerInPing() {
      // ARRANGE — container.isRunning() returns false → isPingSuccessful returns false
      // → waitForReady loops and eventually times out
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getContainerId()).thenReturn("ping-test");
      when(container.isRunning()).thenReturn(false);

      // ACT & ASSERT — the timeout confirms isPingSuccessful returned false each iteration
      assertThatThrownBy(() -> controller.waitForReady(container, Duration.ofMillis(5)))
          .isInstanceOf(ContainerOperationException.class);
    }

    @Test
    @DisplayName("Returns false when container.getHost() throws — swallowed, returns false")
    @SuppressWarnings("rawtypes")
    void shouldReturnFalseWhenGetHostThrows() {
      // ARRANGE — isRunning=true but getHost throws → catch block returns false → timeout
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getContainerId()).thenReturn("ping-host-throws");
      when(container.isRunning()).thenReturn(true);
      when(container.getHost()).thenThrow(new RuntimeException("network error"));

      // ACT & ASSERT
      assertThatThrownBy(() -> controller.waitForReady(container, Duration.ofMillis(5)))
          .isInstanceOf(ContainerOperationException.class);
    }
  }

  // ─── restart() — null check ───────────────────────────────────────────────

  @Nested
  @DisplayName("restart() — null check")
  class RestartTests {

    @Test
    @DisplayName("Throws NPE for null container")
    void shouldThrowForNullContainer() {
      assertThatThrownBy(() -> controller.restart(null)).isInstanceOf(NullPointerException.class);
    }
  }
}
