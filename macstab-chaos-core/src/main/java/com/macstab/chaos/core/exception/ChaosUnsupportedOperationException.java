/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

import lombok.extern.slf4j.Slf4j;

/**
 * Thrown when chaos operation not supported on current system.
 *
 * <p>Example: cgroups v2 not available, kernel too old, missing capabilities.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ChaosUnsupportedOperationException extends ChaosException {

  /**
   * Creates a new unsupported operation exception.
   *
   * @param message detail message describing why the operation is unsupported
   */
  public ChaosUnsupportedOperationException(final String message) {
    super(message);
  }
}
