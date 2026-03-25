/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link ShellDetector}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ShellDetector")
class ShellDetectorTest {

  private GenericContainer<?> container;

  @BeforeEach
  void setUp() {
    container = mock(GenericContainer.class);
  }

  @Nested
  @DisplayName("shell detection")
  class ShellDetection {

    @Test
    @DisplayName("should detect bash when available")
    void shouldDetectBashWhenAvailable() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult bashResult = mockExecResult(0, "/bin/bash", "");
      when(container.execInContainer("which", "/bin/bash")).thenReturn(bashResult);

      // WHEN
      final Shell shell = ShellDetector.detect(container);

      // THEN
      assertThat(shell).isInstanceOf(BashShell.class);
      assertThat(shell.getType()).isEqualTo(ShellType.BASH);
    }

    @Test
    @DisplayName("should fallback to sh when bash not available")
    void shouldFallbackToShWhenBashNotAvailable() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult bashResult = mockExecResult(1, "", "not found");
      when(container.execInContainer("which", "/bin/bash")).thenReturn(bashResult);

      final ExecResult shResult = mockExecResult(0, "/bin/sh", "");
      when(container.execInContainer("which", "/bin/sh")).thenReturn(shResult);

      // WHEN
      final Shell shell = ShellDetector.detect(container);

      // THEN
      assertThat(shell).isInstanceOf(BusyboxShell.class);
      assertThat(shell.getType()).isEqualTo(ShellType.BUSYBOX);
    }

    @Test
    @DisplayName("should throw exception when no shell found")
    void shouldThrowExceptionWhenNoShellFound() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult notFoundResult = mockExecResult(1, "", "not found");
      when(container.execInContainer(any(String.class), any(String.class)))
          .thenReturn(notFoundResult);

      // WHEN / THEN
      assertThatThrownBy(() -> ShellDetector.detect(container))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No shell found");
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("should reject null container")
    void shouldRejectNullContainer() {
      // WHEN / THEN
      assertThatThrownBy(() -> ShellDetector.detect(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container must not be null");
    }
  }

  private ExecResult mockExecResult(final int exitCode, final String stdout, final String stderr) {
    final ExecResult result = mock(ExecResult.class);
    when(result.getExitCode()).thenReturn(exitCode);
    when(result.getStdout()).thenReturn(stdout);
    when(result.getStderr()).thenReturn(stderr);
    return result;
  }
}
