/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

/**
 * Supported shell types.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public enum ShellType {
  /** Bourne Again Shell (most common on Debian, Ubuntu, RHEL) */
  BASH,

  /** Almquist shell via BusyBox (Alpine Linux) */
  ASH,

  /** Busybox shell (Alpine Linux) */
  @Deprecated(forRemoval = true, since = "1.1")
  BUSYBOX,

  /** Debian Almquist Shell (minimal Debian containers) */
  DASH,

  /** Z Shell (macOS default since Catalina) */
  ZSH,

  /** PowerShell (Windows) - future support */
  POWERSHELL
}
