/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

/**
 * Consumer that can throw exceptions.
 *
 * <p>Allows chaos operations that throw checked exceptions (e.g., IOException).
 *
 * @param <T> value type
 * @author Christian Schnapka - Macstab GmbH
 */
@FunctionalInterface
public interface ValueConsumer<T> {

  /**
   * Accept value (may throw exception).
   *
   * @param value value to consume
   * @throws Exception if operation fails
   */
  void accept(T value) throws Exception;
}
