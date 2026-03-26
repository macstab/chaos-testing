/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Edge case tests for {@link AbstractShell}.
 */
@DisplayName("AbstractShell - Edge Cases")
class AbstractShellEdgeCasesTest {

  static class TestShell extends AbstractShell {
    @Override
    public ShellType getType() {
      return ShellType.BASH;
    }

    @Override
    public String getBinary() {
      return "/bin/bash";
    }

    @Override
    public boolean supportsDevTcp() {
      return true;
    }
  }

  @Test
  @DisplayName("exec() should throw when container is null")
  void exec_shouldThrowWhenContainerNull() {
    Shell shell = new TestShell();
    
    assertThatThrownBy(() -> shell.exec(null, "echo test"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("container must not be null");
  }

  @Test
  @DisplayName("exec() should throw when command is null")
  void exec_shouldThrowWhenCommandNull() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    Shell shell = new TestShell();
    
    assertThatThrownBy(() -> shell.exec(container, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("command must not be null");
  }

  @Test
  @DisplayName("exec() should throw when container not running")
  void exec_shouldThrowWhenContainerNotRunning() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(false);
    Shell shell = new TestShell();
    
    assertThatThrownBy(() -> shell.exec(container, "echo test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Container is not running");
  }

  @Test
  @DisplayName("isAvailable() should throw when container is null")
  void isAvailable_shouldThrowWhenContainerNull() {
    Shell shell = new TestShell();
    
    assertThatThrownBy(() -> shell.isAvailable(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("container must not be null");
  }

  @Test
  @DisplayName("isAvailable() should return false when container not running")
  void isAvailable_shouldReturnFalseWhenContainerNotRunning() {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(false);
    Shell shell = new TestShell();
    
    boolean result = shell.isAvailable(container);
    
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("isAvailable() should return false when which fails")
  void isAvailable_shouldReturnFalseWhenWhichFails() throws Exception {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    
    ExecResult result = mock(ExecResult.class);
    when(result.getExitCode()).thenReturn(1); // Not found
    when(container.execInContainer("which", "/bin/bash")).thenReturn(result);
    
    Shell shell = new TestShell();
    boolean available = shell.isAvailable(container);
    
    assertThat(available).isFalse();
  }

  @Test
  @DisplayName("isAvailable() should return true when which succeeds")
  void isAvailable_shouldReturnTrueWhenWhichSucceeds() throws Exception {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    
    ExecResult result = mock(ExecResult.class);
    when(result.getExitCode()).thenReturn(0); // Found
    when(container.execInContainer("which", "/bin/bash")).thenReturn(result);
    
    Shell shell = new TestShell();
    boolean available = shell.isAvailable(container);
    
    assertThat(available).isTrue();
  }

  @Test
  @DisplayName("isAvailable() should return false when exception occurs")
  void isAvailable_shouldReturnFalseWhenExceptionOccurs() throws Exception {
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    when(container.execInContainer("which", "/bin/bash"))
        .thenThrow(new RuntimeException("Test exception"));
    
    Shell shell = new TestShell();
    boolean available = shell.isAvailable(container);
    
    assertThat(available).isFalse();
  }

  @Test
  @DisplayName("buildPortCheckCommand() should reject port < 1")
  void buildPortCheckCommand_shouldRejectPortLessThanOne() {
    Shell shell = new TestShell();
    
    assertThatThrownBy(() -> shell.buildPortCheckCommand(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("port must be in range [1, 65535]");
  }

  @Test
  @DisplayName("buildPortCheckCommand() should reject port > 65535")
  void buildPortCheckCommand_shouldRejectPortGreaterThan65535() {
    Shell shell = new TestShell();
    
    assertThatThrownBy(() -> shell.buildPortCheckCommand(65536))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("port must be in range [1, 65535]");
  }

  @Test
  @DisplayName("buildPortCheckCommand() should accept valid port range")
  void buildPortCheckCommand_shouldAcceptValidPorts() {
    Shell shell = new TestShell();
    
    // Min boundary
    assertThat(shell.buildPortCheckCommand(1)).contains("localhost:1");
    
    // Max boundary
    assertThat(shell.buildPortCheckCommand(65535)).contains("localhost:65535");
    
    // Common ports
    assertThat(shell.buildPortCheckCommand(80)).contains("localhost:80");
    assertThat(shell.buildPortCheckCommand(443)).contains("localhost:443");
    assertThat(shell.buildPortCheckCommand(8080)).contains("localhost:8080");
  }

  @Test
  @DisplayName("buildPortCheckCommand() should include curl with timeouts")
  void buildPortCheckCommand_shouldIncludeCurlWithTimeouts() {
    Shell shell = new TestShell();
    
    String command = shell.buildPortCheckCommand(8080);
    
    assertThat(command).contains("curl");
    assertThat(command).contains("--connect-timeout 1");
    assertThat(command).contains("--max-time 1");
    assertThat(command).contains("localhost:8080");
  }
}
