/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

import java.util.Objects;
import java.util.Set;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base for shell implementations.
 *
 * <p>Provides common validation and execution logic.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public abstract class AbstractShell implements Shell {

  private static final String FLAG_C = "-c";

  @Override
  public ExecResult exec(final GenericContainer<?> container, final String command)
      throws Exception {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(command, "command must not be null");

    if (!container.isRunning()) {
      throw new IllegalStateException("Container is not running");
    }

    log.debug("Executing command via {}: {}", getType(), command);

    return container.execInContainer(getBinary(), FLAG_C, command);
  }

  @Override
  public boolean isAvailable(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return false;
    }

    try {
      final var result = container.execInContainer("which", getBinary());
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      log.debug("Shell {} not available: {}", getType(), e.getMessage());
      return false;
    }
  }

  @Override
  public String buildPortCheckCommand(final int port) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(
          String.format("port must be in range [1, 65535], got: %d", port));
    }

    // Universal curl-based check (works on all shells)
    return String.format(
        "curl -s --connect-timeout 1 --max-time 1 http://localhost:%d >/dev/null 2>&1; "
            + "test $? -eq 0 -o $? -eq 52",
        port);
  }

  /**
   * Returns the set of capabilities this shell supports.
   *
   * <p>Subclasses override to declare their capabilities. The default returns {@link
   * ShellCapability#COMMAND_SUBSTITUTION} only (POSIX baseline).
   *
   * @return immutable set of supported capabilities
   */
  protected Set<ShellCapability> capabilities() {
    return Set.of(ShellCapability.COMMAND_SUBSTITUTION);
  }

  @Override
  public final boolean supports(final ShellCapability capability) {
    Objects.requireNonNull(capability, "capability must not be null");
    return capabilities().contains(capability);
  }
}
