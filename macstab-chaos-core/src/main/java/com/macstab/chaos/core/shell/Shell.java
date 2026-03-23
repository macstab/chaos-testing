/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Shell abstraction for executing commands in containers.
 *
 * <p>Provides shell-specific command execution with proper error handling.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * Shell shell = new BashShell();
 * ExecResult result = shell.exec(container, "curl -s http://localhost:8080");
 *
 * if (result.getExitCode() != 0) {
 *   throw new ChaosOperationFailedException("Command failed: " + result.getStderr());
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface Shell {

  /**
   * Get shell type (BASH, BUSYBOX, DASH, ZSH, etc.).
   *
   * @return shell type
   */
  ShellType getType();

  /**
   * Get shell binary path (e.g., "/bin/bash", "/bin/sh").
   *
   * @return binary path
   */
  String getBinary();

  /**
   * Execute command in container using this shell.
   *
   * @param container container
   * @param command command to execute
   * @return execution result
   * @throws Exception if execution fails
   */
  ExecResult exec(GenericContainer<?> container, String command) throws Exception;

  /**
   * Check if shell is available in container.
   *
   * @param container container
   * @return true if shell binary exists
   */
  boolean isAvailable(GenericContainer<?> container);

  /**
   * Check if shell supports /dev/tcp pseudo-device.
   *
   * <p>bash supports it, busybox sh does not.
   *
   * @return true if supported
   */
  boolean supportsDevTcp();

  /**
   * Build port check command for this shell.
   *
   * <p>Returns platform-specific command to check if port is listening.
   *
   * @param port port to check
   * @return command string
   */
  String buildPortCheckCommand(int port);
}
