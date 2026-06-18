/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.process;

import java.util.Objects;

/**
 * Linux /proc filesystem process command builder.
 *
 * <p>Uses /proc to find and manage processes (fastest, most reliable on Linux).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ProcFsCommandBuilder implements ProcessCommandBuilder {

  /** Creates a /proc-based process command builder. */
  public ProcFsCommandBuilder() {}

  @Override
  public String buildFindProcessCommand(final String processName) {
    Objects.requireNonNull(processName, "processName must not be null");

    return String.format(
        "for pid in $(grep -l '%s' /proc/*/cmdline 2>/dev/null | cut -d/ -f3); do echo $pid; done",
        processName);
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

    return String.format("test -d /proc/%d", pid);
  }

  @Override
  public String buildKillAllProcessesCommand(final String processName) {
    Objects.requireNonNull(processName, "processName must not be null");

    return String.format(
        "for pid in $(grep -l '%s' /proc/*/cmdline 2>/dev/null | cut -d/ -f3); do "
            + "kill -9 $pid 2>/dev/null || true; done; sleep 0.2",
        processName);
  }
}
