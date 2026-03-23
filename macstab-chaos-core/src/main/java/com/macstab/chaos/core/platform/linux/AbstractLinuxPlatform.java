/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform.linux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import java.util.HashMap;
import java.util.Map;

import com.macstab.chaos.core.command.network.IptablesCommandBuilder;
import com.macstab.chaos.core.command.network.NetworkCommandBuilder;
import com.macstab.chaos.core.command.process.ProcFsCommandBuilder;
import com.macstab.chaos.core.command.process.ProcessCommandBuilder;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformType;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.core.shell.ShellDetector;

import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base for all Linux platform implementations.
 *
 * <p>Provides common Linux logic with template methods for distribution-specific behavior.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public abstract class AbstractLinuxPlatform implements Platform {

  private final NetworkCommandBuilder networkCommandBuilder = new IptablesCommandBuilder();
  private final ProcessCommandBuilder processCommandBuilder = new ProcFsCommandBuilder();

  // Default Linux tool mappings (most distributions)
  private static final Map<Tool, ToolMapping> DEFAULT_TOOLS = createDefaultTools();

  private static Map<Tool, ToolMapping> createDefaultTools() {
    final Map<Tool, ToolMapping> tools = new HashMap<>();
    tools.put(Tool.CURL, new ToolMapping("curl", "curl"));
    tools.put(Tool.IPTABLES, new ToolMapping("iptables", "iptables"));
    tools.put(Tool.CA_CERTIFICATES, new ToolMapping("ca-certificates", null));
    tools.put(Tool.PROCPS, new ToolMapping("procps", "ps"));
    tools.put(Tool.IPROUTE, new ToolMapping("iproute2", "ip"));
    tools.put(Tool.PYTHON, new ToolMapping("python3", "python3"));
    tools.put(Tool.STRESS_NG, new ToolMapping("stress-ng", "stress-ng"));
    return tools;
  }

  /** Package name and binary name for a tool. */
  protected record ToolMapping(String packageName, String binaryName) {}

  /**
   * Override for platform-specific tool mappings.
   *
   * @return map of tool overrides (empty = use defaults)
   */
  protected Map<Tool, ToolMapping> getToolOverrides() {
    return Map.of();
  }

  @Override
  public final PlatformType getType() {
    return PlatformType.LINUX;
  }

  @Override
  public List<String> getRequiredTools() {
    return List.of("curl", "iptables");
  }

  @Override
  public void validatePrerequisites(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    final List<String> missing = new ArrayList<>();

    for (final String tool : getRequiredTools()) {
      if (!hasCommand(container, tool)) {
        missing.add(tool);
      }
    }

    if (!missing.isEmpty()) {
      throw new ChaosOperationFailedException(
          String.format(
              "Missing required tools: %s. " + "Please install them in your container image.",
              String.join(", ", missing)));
    }

    log.debug("Prerequisites validated for {}", getDistribution());
  }

  @Override
  public Shell getDefaultShell() {
    // Detect shell at runtime (container might have bash or just sh)
    return new Shell() {
      private Shell delegate;

      private Shell getDelegate(final GenericContainer<?> container) {
        if (delegate == null) {
          delegate = ShellDetector.detect(container);
        }
        return delegate;
      }

      @Override
      public com.macstab.chaos.core.shell.ShellType getType() {
        return delegate != null ? delegate.getType() : com.macstab.chaos.core.shell.ShellType.BASH;
      }

      @Override
      public String getBinary() {
        return delegate != null ? delegate.getBinary() : "/bin/sh";
      }

      @Override
      public org.testcontainers.containers.Container.ExecResult exec(
          GenericContainer<?> container, String command) throws Exception {
        return getDelegate(container).exec(container, command);
      }

      @Override
      public boolean isAvailable(GenericContainer<?> container) {
        return getDelegate(container).isAvailable(container);
      }

      @Override
      public boolean supportsDevTcp() {
        return delegate != null && delegate.supportsDevTcp();
      }

      @Override
      public String buildPortCheckCommand(int port) {
        return delegate != null
            ? delegate.buildPortCheckCommand(port)
            : String.format(
                "curl -s --connect-timeout 1 --max-time 1 http://localhost:%d >/dev/null 2>&1; test $? -eq 0 -o $? -eq 52",
                port);
      }
    };
  }

  @Override
  public NetworkCommandBuilder getNetworkCommandBuilder() {
    return networkCommandBuilder;
  }

  @Override
  public ProcessCommandBuilder getProcessCommandBuilder() {
    return processCommandBuilder;
  }

  @Override
  public boolean hasCommand(final GenericContainer<?> container, final String command) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(command, "command must not be null");

    if (!container.isRunning()) {
      return false;
    }

    try {
      final var result = container.execInContainer("which", command);
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      log.debug("Command '{}' not found: {}", command, e.getMessage());
      return false;
    }
  }

  @Override
  public boolean supportsCapability(final String capability) {
    Objects.requireNonNull(capability, "capability must not be null");

    return switch (capability) {
      case "NET_ADMIN" -> true; // Required for iptables
      case "/proc" -> true; // Linux has /proc
      default -> false;
    };
  }

  @Override
  public String getPackageName(final Tool tool) {
    Objects.requireNonNull(tool, "tool must not be null");

    final ToolMapping mapping = getToolMapping(tool);
    if (mapping == null) {
      throw new UnsupportedOperationException(
          String.format("Tool %s not supported on %s", tool, getDistribution()));
    }

    return mapping.packageName();
  }

  @Override
  public String getBinaryName(final Tool tool) {
    Objects.requireNonNull(tool, "tool must not be null");

    final ToolMapping mapping = getToolMapping(tool);
    if (mapping == null) {
      throw new UnsupportedOperationException(
          String.format("Tool %s not supported on %s", tool, getDistribution()));
    }

    return mapping.binaryName() != null ? mapping.binaryName() : mapping.packageName();
  }

  private ToolMapping getToolMapping(final Tool tool) {
    // Check overrides first, then defaults
    final Map<Tool, ToolMapping> overrides = getToolOverrides();
    return overrides.getOrDefault(tool, DEFAULT_TOOLS.get(tool));
  }
}
