/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

/**
 * Bash shell implementation.
 *
 * <p>Most common on Debian, Ubuntu, RHEL, CentOS, Arch Linux.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>Supports /dev/tcp
 *   <li>Process substitution
 *   <li>Arrays
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class BashShell extends AbstractShell {

  @Override
  public ShellType getType() {
    return ShellType.BASH;
  }

  @Override
  public String getBinary() {
    return "/bin/bash";
  }

  @Override
  public boolean supportsDevTcp() {
    return true;
  }
}
