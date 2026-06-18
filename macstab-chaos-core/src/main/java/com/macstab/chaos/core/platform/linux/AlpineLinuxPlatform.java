/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform.linux;

import java.util.Map;

import com.macstab.chaos.core.platform.Tool;

/**
 * Alpine Linux platform (minimal containers, e.g., redis:7.4-alpine, nginx:alpine).
 *
 * <p><strong>Package differences from Debian:</strong>
 *
 * <ul>
 *   <li>Uses musl libc instead of glibc
 *   <li>Busybox shell instead of bash by default
 *   <li>Some package names differ
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class AlpineLinuxPlatform extends AbstractLinuxPlatform {

  /** Creates an Alpine Linux platform instance. */
  public AlpineLinuxPlatform() {}

  @Override
  public String getDistribution() {
    return "alpine";
  }

  @Override
  protected Map<Tool, ToolMapping> getToolOverrides() {
    // Alpine mostly uses same names, but some differ
    return Map.of(
        Tool.PYTHON, new ToolMapping("python3", "python3"),
        Tool.FAKETIME, new ToolMapping("libfaketime", "faketime"));
  }
}
