/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

/**
 * Base exception for all chaos operations.
 *
 * <p>Unchecked exception (extends {@link RuntimeException}) to avoid verbose exception handling in
 * test code.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public class ChaosException extends RuntimeException {

  public ChaosException(final String message) {
    super(message);
  }

  public ChaosException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
