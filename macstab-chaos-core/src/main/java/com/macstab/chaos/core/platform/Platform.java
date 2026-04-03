/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.command.http.HttpCommandBuilder;
import com.macstab.chaos.core.command.network.NetworkCommandBuilder;
import com.macstab.chaos.core.command.process.ProcessCommandBuilder;
import com.macstab.chaos.core.shell.Shell;

/**
 * Platform abstraction for container operating systems.
 *
 * <p>Provides platform-specific information and delegates for command building and execution.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * Platform platform = PlatformDetector.detect(container);
 * Shell shell = platform.getDefaultShell();
 * NetworkCommandBuilder network = platform.getNetworkCommandBuilder();
 *
 * String cmd = network.buildAddRedirectCommand(6379, 16379);
 * shell.exec(container, cmd);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see PlatformDetector
 * @see Shell
 * @see NetworkCommandBuilder
 */
public interface Platform {

  /**
   * Get platform type (LINUX, BSD, WINDOWS, MACOS).
   *
   * @return platform type
   */
  PlatformType getType();

  /**
   * Get distribution name (debian, alpine, ubuntu, rhel, etc.).
   *
   * @return distribution name (lowercase)
   */
  String getDistribution();

  /**
   * Get list of required tools for chaos operations.
   *
   * <p>These tools must be installed before using chaos modules. The framework validates their
   * presence via {@link #getBinaryName(Tool)} resolution.
   *
   * @return immutable list of required {@link Tool} entries
   */
  List<Tool> getRequiredTools();

  /**
   * Validate that all required tools are present in the container.
   *
   * @param container container to validate
   * @throws com.macstab.chaos.core.exception.ChaosOperationFailedException if tools missing
   */
  void validatePrerequisites(GenericContainer<?> container);

  /**
   * Get default shell for this platform.
   *
   * @return shell instance
   */
  Shell getDefaultShell();

  /**
   * Get network command builder for this platform.
   *
   * <p>Provides platform-specific network commands (iptables, nftables, pf, etc.).
   *
   * @return network command builder
   */
  NetworkCommandBuilder getNetworkCommandBuilder();

  /**
   * Get process command builder for this platform.
   *
   * <p>Provides platform-specific process management commands (proc-fs, ps, etc.).
   *
   * @return process command builder
   */
  ProcessCommandBuilder getProcessCommandBuilder();

  /**
   * Get HTTP command builder for this platform.
   *
   * <p>Provides platform-specific HTTP commands (curl, wget, PowerShell, fetch).
   *
   * <p>Implementation may use fallback chain (e.g., curl → wget on Alpine).
   *
   * @return HTTP command builder
   */
  HttpCommandBuilder getHttpCommandBuilder();

  /**
   * Check if container has specific command available.
   *
   * @param container container
   * @param command command name (e.g., "curl", "bash")
   * @return true if command available
   */
  boolean hasCommand(GenericContainer<?> container, String command);

  /**
   * Check if platform supports specific capability.
   *
   * <p>Examples: "NET_ADMIN", "SYS_ADMIN", "/proc"
   *
   * @param capability capability name
   * @return true if supported
   */
  boolean supportsCapability(String capability);

  /**
   * Get platform-specific package name for a tool.
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>Debian: {@code getPackageName(Tool.PROCPS)} → "procps"
   *   <li>RHEL: {@code getPackageName(Tool.PROCPS)} → "procps-ng"
   *   <li>Alpine: {@code getPackageName(Tool.PYTHON)} → "python3"
   * </ul>
   *
   * @param tool tool
   * @return package name for this platform
   */
  String getPackageName(Tool tool);

  /**
   * Get binary name for a tool (may differ from package name).
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>{@code getBinaryName(Tool.PYTHON)} → "python3"
   *   <li>{@code getBinaryName(Tool.CURL)} → "curl"
   * </ul>
   *
   * @param tool tool
   * @return binary name
   */
  String getBinaryName(Tool tool);
}
