/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

import java.util.Set;

/**
 * Bash shell implementation.
 *
 * <p>Most common on Debian, Ubuntu, RHEL, CentOS, Arch Linux.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class BashShell extends AbstractShell {

  /** Creates a bash shell instance. */
  public BashShell() {}

  private static final Set<ShellCapability> CAPABILITIES =
      Set.of(
          ShellCapability.COMMAND_SUBSTITUTION,
          ShellCapability.DEV_TCP,
          ShellCapability.PROCESS_SUBSTITUTION,
          ShellCapability.BRACE_EXPANSION,
          ShellCapability.EXTENDED_TEST,
          ShellCapability.ARRAYS,
          ShellCapability.ASSOCIATIVE_ARRAYS);

  @Override
  public ShellType getType() {
    return ShellType.BASH;
  }

  @Override
  public String getBinary() {
    return "/bin/bash";
  }

  @SuppressWarnings("removal")
  @Override
  public boolean supportsDevTcp() {
    return true;
  }

  @Override
  protected Set<ShellCapability> capabilities() {
    return CAPABILITIES;
  }
}
