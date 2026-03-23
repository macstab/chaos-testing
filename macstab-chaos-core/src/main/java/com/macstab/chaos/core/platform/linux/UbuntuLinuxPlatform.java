/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform.linux;

/**
 * Ubuntu Linux platform (e.g., ubuntu:22.04, ubuntu:24.04).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class UbuntuLinuxPlatform extends AbstractLinuxPlatform {

  @Override
  public String getDistribution() {
    return "ubuntu";
  }
}
