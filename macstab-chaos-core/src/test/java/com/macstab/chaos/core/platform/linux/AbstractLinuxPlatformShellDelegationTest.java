/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform.linux;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.shell.*;

/**
 * Tests for AbstractLinuxPlatform shell delegation logic.
 * 
 * Covers the lazy shell detection and delegation paths.
 */
@DisplayName("AbstractLinuxPlatform - Shell Delegation")
class AbstractLinuxPlatformShellDelegationTest {

  static class TestLinuxPlatform extends AbstractLinuxPlatform {
    @Override
    public String getDistribution() {
      return "test-linux";
    }
  }

  @Test
  @DisplayName("getDefaultShell() should return non-null shell")
  void getDefaultShell_shouldReturnNonNull() {
    Platform platform = new TestLinuxPlatform();
    assertThat(platform.getDefaultShell()).isNotNull();
  }

  @Test
  @DisplayName("Shell getType() should return BASH when delegate not yet initialized")
  void shellGetType_shouldReturnBashBeforeInit() {
    Platform platform = new TestLinuxPlatform();
    Shell shell = platform.getDefaultShell();
    
    // Before exec() call, delegate is null, should return BASH as default
    ShellType type = shell.getType();
    assertThat(type).isEqualTo(ShellType.BASH);
  }

  @Test
  @DisplayName("Shell getBinary() should return /bin/sh when delegate not yet initialized")
  void shellGetBinary_shouldReturnShBeforeInit() {
    Platform platform = new TestLinuxPlatform();
    Shell shell = platform.getDefaultShell();
    
    // Before exec() call, delegate is null, should return /bin/sh as default
    String binary = shell.getBinary();
    assertThat(binary).isEqualTo("/bin/sh");
  }

  @Test
  @DisplayName("Shell exec() should trigger lazy initialization")
  void shellExec_shouldTriggerLazyInit() throws Exception {
    Platform platform = new TestLinuxPlatform();
    Shell shell = platform.getDefaultShell();
    
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    
    // Mock bash detection
    ExecResult bashResult = mock(ExecResult.class);
    when(bashResult.getExitCode()).thenReturn(0);
    when(container.execInContainer("which", "bash")).thenReturn(bashResult);
    
    // Mock command execution
    ExecResult execResult = mock(ExecResult.class);
    when(execResult.getExitCode()).thenReturn(0);
    when(execResult.getStdout()).thenReturn("output");
    when(container.execInContainer(anyString(), anyString(), anyString())).thenReturn(execResult);
    
    // Execute command - this triggers lazy shell detection
    ExecResult result = shell.exec(container, "echo test");
    
    assertThat(result).isNotNull();
    assertThat(result.getExitCode()).isZero();
  }

  @Test
  @DisplayName("Shell isAvailable() should trigger lazy initialization")
  void shellIsAvailable_shouldTriggerLazyInit() throws Exception {
    Platform platform = new TestLinuxPlatform();
    Shell shell = platform.getDefaultShell();
    
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    
    // Mock bash detection
    ExecResult bashResult = mock(ExecResult.class);
    when(bashResult.getExitCode()).thenReturn(0);
    when(container.execInContainer("which", "bash")).thenReturn(bashResult);
    
    // Check availability - triggers lazy detection
    boolean available = shell.isAvailable(container);
    
    assertThat(available).isTrue();
  }

  @Test
  @DisplayName("Shell supportsDevTcp() should work after lazy init")
  void shellSupportsDevTcp_shouldWorkAfterInit() throws Exception {
    Platform platform = new TestLinuxPlatform();
    Shell shell = platform.getDefaultShell();
    
    @SuppressWarnings("resource")
    GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    
    // Mock bash detection
    ExecResult bashResult = mock(ExecResult.class);
    when(bashResult.getExitCode()).thenReturn(0);
    when(container.execInContainer("which", "bash")).thenReturn(bashResult);
    
    // Trigger lazy init via exec
    ExecResult execResult = mock(ExecResult.class);
    when(execResult.getExitCode()).thenReturn(0);
    when(container.execInContainer(anyString(), anyString(), anyString())).thenReturn(execResult);
    shell.exec(container, "test");
    
    // Now supportsDevTcp should work (bash supports it)
    boolean supports = shell.supportsDevTcp();
    assertThat(supports).isTrue(); // Bash supports /dev/tcp
  }

  @Test
  @DisplayName("Shell buildPortCheckCommand() should work")
  void shellBuildPortCheckCommand_shouldWork() {
    Platform platform = new TestLinuxPlatform();
    Shell shell = platform.getDefaultShell();
    
    String command = shell.buildPortCheckCommand(8080);
    
    assertThat(command).contains("localhost:8080");
    assertThat(command).contains("curl");
  }

  @Test
  @DisplayName("Shell buildPortCheckCommand() should fallback before lazy init")
  void shellBuildPortCheckCommand_shouldFallbackBeforeInit() {
    Platform platform = new TestLinuxPlatform();
    Shell shell = platform.getDefaultShell();
    
    // Before lazy init (delegate == null), should use fallback
    String command = shell.buildPortCheckCommand(8080);
    
    assertThat(command).isNotNull();
    assertThat(command).contains("8080");
    assertThat(command).contains("curl");
  }

  @Test
  @DisplayName("Multiple shell instances should work independently")
  void multipleShellInstances_shouldWorkIndependently() {
    Platform platform = new TestLinuxPlatform();
    Shell shell1 = platform.getDefaultShell();
    Shell shell2 = platform.getDefaultShell();
    
    assertThat(shell1).isNotNull();
    assertThat(shell2).isNotNull();
    assertThat(shell1).isNotSameAs(shell2);
  }
}
