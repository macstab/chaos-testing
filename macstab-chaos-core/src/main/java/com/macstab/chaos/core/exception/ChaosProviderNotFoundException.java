/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

import lombok.extern.slf4j.Slf4j;

/**
 * Thrown when chaos provider implementation not found on classpath.
 *
 * <p>Indicates user needs to add module dependency (e.g., macstab-chaos-cpu).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ChaosProviderNotFoundException extends ChaosException {

  /**
   * Creates a new provider-not-found exception.
   *
   * @param message human-readable message including the missing dependency coordinate
   */
  public ChaosProviderNotFoundException(final String message) {
    super(message);
  }
}
