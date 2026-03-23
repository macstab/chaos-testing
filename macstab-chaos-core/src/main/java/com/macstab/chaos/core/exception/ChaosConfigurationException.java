/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

/**
 * Thrown when chaos configuration is invalid.
 *
 * <p>Example: Invalid percentage (not in 1-100), invalid memory format, etc.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ChaosConfigurationException extends ChaosException {

  public ChaosConfigurationException(final String message) {
    super(message);
  }
}
