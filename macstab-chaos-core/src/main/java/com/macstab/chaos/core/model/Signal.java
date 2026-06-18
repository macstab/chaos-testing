/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.model;

/**
 * Unix process signals.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public enum Signal {
  /** Terminate gracefully (default). */
  SIGTERM(15),

  /** Kill immediately (cannot be caught). */
  SIGKILL(9),

  /** Stop/pause process. */
  SIGSTOP(19),

  /** Continue paused process. */
  SIGCONT(18);

  private final int value;

  Signal(final int value) {
    this.value = value;
  }

  /**
   * Returns the numeric signal value.
   *
   * @return signal number (e.g., 9 for SIGKILL, 15 for SIGTERM)
   */
  public int value() {
    return value;
  }
}
