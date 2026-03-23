/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

/**
 * Thrown when chaos operation not supported on current system.
 *
 * <p>Example: cgroups v2 not available, kernel too old, missing capabilities.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ChaosUnsupportedOperationException extends ChaosException {

  public ChaosUnsupportedOperationException(final String message) {
    super(message);
  }
}
