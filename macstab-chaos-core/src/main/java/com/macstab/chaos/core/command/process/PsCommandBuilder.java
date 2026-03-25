/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.process;

import java.util.Objects;

/**
 * Portable ps command process builder.
 *
 * <p>Uses ps command (works on Linux, BSD, macOS - slower than /proc but more portable).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class PsCommandBuilder implements ProcessCommandBuilder {

  @Override
  public String buildFindProcessCommand(final String processName) {
    Objects.requireNonNull(processName, "processName must not be null");

    return String.format("ps aux | grep '%s' | grep -v grep | awk '{print $2}'", processName);
  }

  @Override
  public String buildKillProcessCommand(final int pid) {
    if (pid <= 0) {
      throw new IllegalArgumentException("pid must be positive, got: " + pid);
    }

    return String.format("kill -9 %d 2>/dev/null || true", pid);
  }

  @Override
  public String buildCheckProcessCommand(final int pid) {
    if (pid <= 0) {
      throw new IllegalArgumentException("pid must be positive, got: " + pid);
    }

    return String.format("ps -p %d >/dev/null 2>&1", pid);
  }

  @Override
  public String buildKillAllProcessesCommand(final String processName) {
    Objects.requireNonNull(processName, "processName must not be null");

    return String.format(
        "ps aux | grep '%s' | grep -v grep | awk '{print $2}' | "
            + "xargs -r kill -9 2>/dev/null || true; sleep 0.2",
        processName);
  }
}
