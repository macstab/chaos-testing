/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.model;

import java.util.Objects;

/**
 * Process information (name + PID).
 *
 * @param pid process ID
 * @param name process name
 * @author Christian Schnapka - Macstab GmbH
 */
public record ProcessInfo(int pid, String name) {

  /**
   * Compact constructor that validates PID is positive and name is non-null.
   *
   * @throws IllegalArgumentException if pid &le; 0
   * @throws NullPointerException if name is null
   */
  public ProcessInfo {
    if (pid <= 0) {
      throw new IllegalArgumentException("PID must be positive, got: " + pid);
    }
    Objects.requireNonNull(name, "name must not be null");
  }
}
