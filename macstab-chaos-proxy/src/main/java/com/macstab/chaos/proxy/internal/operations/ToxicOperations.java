/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations;

import java.io.IOException;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.proxy.internal.operations.toxic.ToxicConfig;

/**
 * Toxic operations for Toxiproxy proxies.
 *
 * <p>Manages creation, deletion, and validation of toxics (network fault injections) on proxies.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@link ToxicOperationsManager} - Default implementation
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ToxicOperations {

  /**
   * Add a toxic to a proxy.
   *
   * <p>If the toxic already exists, this operation is idempotent (skips creation).
   *
   * @param container target container
   * @param proxyName proxy name
   * @param config toxic configuration
   * @throws IOException if toxic creation fails
   * @throws IllegalStateException if container is not running
   */
  void addToxic(GenericContainer<?> container, String proxyName, ToxicConfig config)
      throws IOException;

  /**
   * Remove a toxic from a proxy.
   *
   * @param container target container
   * @param proxyName proxy name
   * @param toxicName toxic name to remove
   * @throws IOException if toxic removal fails
   */
  void removeToxic(GenericContainer<?> container, String proxyName, String toxicName)
      throws IOException;

  /**
   * Check if a toxic exists on a proxy.
   *
   * @param container target container
   * @param proxyName proxy name
   * @param toxicName toxic name to check
   * @return true if toxic exists, false otherwise
   */
  boolean toxicExists(GenericContainer<?> container, String proxyName, String toxicName);

  /**
   * Remove all toxics from a proxy.
   *
   * @param container target container
   * @param proxyName proxy name
   * @throws IOException if removal fails
   */
  void removeAllToxics(GenericContainer<?> container, String proxyName) throws IOException;
}
