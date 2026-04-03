/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

/**
 * Busybox shell implementation.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @deprecated Use {@link AshShell} instead. BusyBox {@code /bin/sh} on Alpine is the ash applet.
 */
@Deprecated(forRemoval = true, since = "1.0")
public final class BusyboxShell extends AbstractShell {

  @Override
  public ShellType getType() {
    return ShellType.BUSYBOX;
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
}
