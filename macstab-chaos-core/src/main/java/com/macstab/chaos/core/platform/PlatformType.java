/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform;

/**
 * Supported platform types.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public enum PlatformType {
  /** Linux (Debian, Alpine, RHEL, Ubuntu, etc.) */
  LINUX,

  /** BSD (FreeBSD, OpenBSD) - future support */
  BSD,

  /** macOS - future support */
  MACOS,

  /** Windows - future support */
  WINDOWS
}
