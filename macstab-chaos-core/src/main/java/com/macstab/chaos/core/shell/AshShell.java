/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

import java.util.Set;

/**
 * Almquist shell (ash) via BusyBox — the default shell on Alpine Linux containers.
 *
 * <p>{@code /bin/sh} on Alpine is a symlink to {@code /bin/busybox}, which dispatches to the {@code
 * ash} applet. Ash is POSIX-compliant but does not support bash-specific features (process
 * substitution, brace expansion, arrays, {@code /dev/tcp}).
 *
 * <p><strong>Capabilities:</strong>
 *
 * <ul>
 *   <li>{@link ShellCapability#COMMAND_SUBSTITUTION} — POSIX {@code $()} syntax
 * </ul>
 *
 * <p><strong>Not supported:</strong>
 *
 * <ul>
 *   <li>{@code /dev/tcp} — use {@code curl} or {@code nc} instead
 *   <li>Process substitution ({@code <()}) — use temp files
 *   <li>Brace expansion ({@code {1..10}}) — use {@code seq}
 *   <li>Extended test ({@code [[ ]]}) — use POSIX {@code [ ]}
 *   <li>Arrays — use positional parameters or temp files
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ShellDetector
 */
public final class AshShell extends AbstractShell {

  /** Ash supports only POSIX command substitution. */
  private static final Set<ShellCapability> CAPABILITIES =
      Set.of(ShellCapability.COMMAND_SUBSTITUTION);

  @Override
  public ShellType getType() {
    return ShellType.ASH;
  }

  @Override
  public String getBinary() {
    return "/bin/sh";
  }

  @SuppressWarnings("removal")
  @Override
  public boolean supportsDevTcp() {
    return false;
  }

  @Override
  protected Set<ShellCapability> capabilities() {
    return CAPABILITIES;
  }
}
