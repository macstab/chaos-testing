/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations;

import java.io.IOException;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;

/**
 * Proxy CRUD operations for Toxiproxy.
 *
 * <p>Manages creation, deletion, and validation of TCP proxies through the Toxiproxy API.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@link ProxyOperationsManager} - Default implementation
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ProxyOperations {

  /**
   * Create a new proxy for a TCP service.
   *
   * <p>Sets up the proxy in Toxiproxy API and configures network port redirection.
   *
   * @param container target container
   * @param config proxy configuration
   * @return proxy configuration (may include container hostname)
   * @throws IOException if proxy creation fails
   * @throws IllegalStateException if container is not running
   */
  ProxyConfiguration createProxy(GenericContainer<?> container, ProxyConfiguration config)
      throws IOException;

  /**
   * Delete a proxy and remove its network redirection.
   *
   * @param container target container
   * @param proxyName proxy name to delete
   * @throws IOException if deletion fails
   */
  void deleteProxy(GenericContainer<?> container, String proxyName) throws IOException;

  /**
   * Check if a proxy exists in Toxiproxy API.
   *
   * @param container target container
   * @param proxyName proxy name to check
   * @return true if proxy exists, false otherwise
   */
  boolean proxyExists(GenericContainer<?> container, String proxyName);

  /**
   * Delete all proxies in the container.
   *
   * <p>Removes all proxy configurations and network redirections.
   *
   * @param container target container
   * @throws IOException if deletion fails
   */
  void deleteAllProxies(GenericContainer<?> container) throws IOException;
}
