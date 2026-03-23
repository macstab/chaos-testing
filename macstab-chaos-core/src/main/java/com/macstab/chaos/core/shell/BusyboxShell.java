/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

/**
 * Busybox shell implementation.
 *
 * <p>Used in Alpine Linux (minimal containers).
 *
 * <p><strong>Limitations:</strong>
 *
 * <ul>
 *   <li>No /dev/tcp support
 *   <li>No process substitution
 *   <li>No arrays
 *   <li>Limited built-ins
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class BusyboxShell extends AbstractShell {

  @Override
  public ShellType getType() {
    return ShellType.BUSYBOX;
  }

  @Override
  public String getBinary() {
    return "/bin/sh";
  }

  @Override
  public boolean supportsDevTcp() {
    return false;
  }
}
