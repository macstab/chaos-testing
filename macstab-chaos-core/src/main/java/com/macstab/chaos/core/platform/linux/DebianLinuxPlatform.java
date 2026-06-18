/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform.linux;

/**
 * Debian Linux platform (including official Docker images like redis:7.4, postgres:16).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class DebianLinuxPlatform extends AbstractLinuxPlatform {

  /** Creates a Debian Linux platform instance. */
  public DebianLinuxPlatform() {}

  @Override
  public String getDistribution() {
    return "debian";
  }
}
