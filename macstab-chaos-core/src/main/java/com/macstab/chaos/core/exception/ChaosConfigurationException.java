/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

import lombok.extern.slf4j.Slf4j;

/**
 * Thrown when chaos configuration is invalid.
 *
 * <p>Example: Invalid percentage (not in 1-100), invalid memory format, etc.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ChaosConfigurationException extends ChaosException {

  /**
   * Creates a new configuration exception.
   *
   * @param message detail message describing the invalid configuration
   */
  public ChaosConfigurationException(final String message) {
    super(message);
  }
}
