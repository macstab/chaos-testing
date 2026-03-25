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
 * Unit tests for {@link BashShell}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("BashShell")
class BashShellTest {

  private Shell shell;
  private GenericContainer<?> container;

  @BeforeEach
  void setUp() {
    shell = new BashShell();
    container = mock(GenericContainer.class);
  }

  @Nested
  @DisplayName("shell properties")
  class ShellProperties {

    @Test
    @DisplayName("should return bash type")
    void shouldReturnBashType() {
      assertThat(shell.getType()).isEqualTo(ShellType.BASH);
    }

    @Test
    @DisplayName("should return bash binary path")
    void shouldReturnBashBinaryPath() {
      assertThat(shell.getBinary()).isEqualTo("/bin/bash");
    }

    @Test
    @DisplayName("should support /dev/tcp")
    void shouldSupportDevTcp() {
      assertThat(shell.supportsDevTcp()).isTrue();
    }
  }

  @Nested
  @DisplayName("availability detection")
  class AvailabilityDetection {

    @Test
    @DisplayName("should detect bash availability")
    void shouldDetectBashAvailability() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult result = mockExecResult(0, "/bin/bash", "");
      when(container.execInContainer("which", "/bin/bash")).thenReturn(result);

      // WHEN
      final boolean available = shell.isAvailable(container);

      // THEN
      assertThat(available).isTrue();
    }

    @Test
    @DisplayName("should detect bash unavailability")
    void shouldDetectBashUnavailability() throws Exception {
      // GIVEN
      when(container.isRunning()).thenReturn(true);
      final ExecResult result = mockExecResult(1, "", "not found");
      when(container.execInContainer("which", "/bin/bash")).thenReturn(result);

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
