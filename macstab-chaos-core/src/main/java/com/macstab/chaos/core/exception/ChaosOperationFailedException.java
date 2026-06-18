/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

import lombok.extern.slf4j.Slf4j;

/**
 * Thrown when chaos operation fails.
 *
 * <p>Example: cgroups write failed, stress-ng failed to start, tc command failed.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public class ChaosOperationFailedException extends ChaosException {

  /**
   * Creates a new operation failed exception.
   *
   * @param message detail message describing the failed operation
   */
  public ChaosOperationFailedException(final String message) {
    super(message);
  }

  /**
   * Creates a new operation failed exception with a root cause.
   *
   * @param message detail message
   * @param cause root cause exception
   */
  public ChaosOperationFailedException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
