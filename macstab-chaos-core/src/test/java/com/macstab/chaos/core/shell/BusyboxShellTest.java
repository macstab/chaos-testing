/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link BusyboxShell}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("BusyboxShell")
class BusyboxShellTest {

  private Shell shell;
  private GenericContainer<?> container;

  @BeforeEach
  void setUp() {
    shell = new BusyboxShell();
    container = mock(GenericContainer.class);
  }

  @Nested
  @DisplayName("shell properties")
  class ShellProperties {

    @Test
    @DisplayName("should return sh type")
    void shouldReturnShType() {
      assertThat(shell.getType()).isEqualTo(ShellType.BUSYBOX);
    }

    @Test
    @DisplayName("should return sh binary path")
    void shouldReturnShBinaryPath() {
      assertThat(shell.getBinary()).isEqualTo("/bin/sh");
    }

    @Test
    @DisplayName("should not support /dev/tcp")
    void shouldNotSupportDevTcp() {
      assertThat(shell.supportsDevTcp()).isFalse();
    }
  }

  @Nested
  @DisplayName("availability detection")
  class AvailabilityDetection {

    @Test
    @DisplayName("should detect sh availability")
    void shouldDetectShAvailability() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult result = mockExecResult(0, "/bin/sh", "");
      when(container.execInContainer("which", "/bin/sh")).thenReturn(result);

      // WHEN
      final boolean available = shell.isAvailable(container);

      // THEN
      assertThat(available).isTrue();
    }

    @Test
    @DisplayName("should detect sh unavailability")
    void shouldDetectShUnavailability() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult result = mockExecResult(1, "", "not found");
      when(container.execInContainer("which", "/bin/sh")).thenReturn(result);

      // WHEN
      final boolean available = shell.isAvailable(container);

      // THEN
      assertThat(available).isFalse();
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
