/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

/**
 * Thrown when chaos provider implementation not found on classpath.
 *
 * <p>Indicates user needs to add module dependency (e.g., macstab-chaos-cpu).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ChaosProviderNotFoundException extends ChaosException {

  public ChaosProviderNotFoundException(final String message) {
    super(message);
  }
}
