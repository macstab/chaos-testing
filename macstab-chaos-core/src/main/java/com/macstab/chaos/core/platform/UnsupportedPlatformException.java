/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * Thrown when platform detection fails or platform is not supported.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class UnsupportedPlatformException extends ChaosOperationFailedException {

  public UnsupportedPlatformException(final String message) {
    super(message);
  }

  public UnsupportedPlatformException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
