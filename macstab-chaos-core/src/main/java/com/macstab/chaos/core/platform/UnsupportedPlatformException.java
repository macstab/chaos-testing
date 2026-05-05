/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * Thrown when platform detection fails or platform is not supported.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class UnsupportedPlatformException extends ChaosOperationFailedException {

  /**
   *  Creates a new instance of {@code UnsupportedPlatformException} with the specified detail message.
   * @param message   the detail message (which is saved for later retrieval by the {@link #getMessage()} method)
   */
  public UnsupportedPlatformException(final String message) {
    super(message);
  }

  /**
   * Creates a new instance of {@code UnsupportedPlatformException} with the specified detail message and cause.
   * @param message   the detail message (which is saved for later retrieval by the {@link #getMessage()} method)
   * @param cause     the cause (which is saved for later retrieval by the {@link #getCause()} method)
   */
  public UnsupportedPlatformException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
