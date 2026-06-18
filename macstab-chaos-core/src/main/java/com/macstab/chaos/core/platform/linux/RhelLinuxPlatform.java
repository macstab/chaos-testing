/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform.linux;

import java.util.Map;

import com.macstab.chaos.core.platform.Tool;

/**
 * RHEL/CentOS/Rocky/Alma Linux platform.
 *
 * <p><strong>Package differences from Debian:</strong>
 *
 * <ul>
 *   <li>Uses {@code procps-ng} instead of {@code procps}
 *   <li>Uses {@code ca-bundle} instead of {@code ca-certificates}
 *   <li>Uses {@code iproute} instead of {@code iproute2}
 *   <li>Python version may differ (python39, python3, etc.)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RhelLinuxPlatform extends AbstractLinuxPlatform {

  /** Creates a RHEL Linux platform instance. */
  public RhelLinuxPlatform() {}

  @Override
  public String getDistribution() {
    return "rhel";
  }

  @Override
  protected Map<Tool, ToolMapping> getToolOverrides() {
    return Map.of(
        Tool.PROCPS, new ToolMapping("procps-ng", "ps"),
        Tool.CA_CERTIFICATES, new ToolMapping("ca-certificates", null),
        Tool.IPROUTE, new ToolMapping("iproute", "ip"),
        Tool.PYTHON, new ToolMapping("python3", "python3"));
  }
}
