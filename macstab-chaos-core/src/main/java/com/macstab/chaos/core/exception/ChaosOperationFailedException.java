/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

/**
 * Thrown when chaos operation fails.
 *
 * <p>Example: cgroups write failed, stress-ng failed to start, tc command failed.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChaosOperationFailedException extends ChaosException {

  public ChaosOperationFailedException(final String message) {
    super(message);
  }

  public ChaosOperationFailedException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
