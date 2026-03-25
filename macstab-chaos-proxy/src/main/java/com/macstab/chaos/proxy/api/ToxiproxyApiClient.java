/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.api;

import java.io.IOException;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;

/**
 * Client for Toxiproxy HTTP API operations.
 *
 * <p>Encapsulates all HTTP communication with the Toxiproxy API server, including proxy
 * management, toxic configuration, and health checks.
 *
 * <p><strong>Thread-safety:</strong> Implementations must be thread-safe.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ToxiproxyApiClient {

  /**
   * Check if Toxiproxy API is responding.
   *
   * @param container container running Toxiproxy
   * @param shell shell for command execution
   * @return true if API responds successfully
   */
  boolean isApiReady(GenericContainer<?> container, Shell shell);

  /**
   * Check if a proxy exists in the API.
   *
   * @param container container
   * @param shell shell
   * @param proxyName proxy name
   * @return true if proxy exists
   * @throws IOException if API communication fails
   */
  boolean proxyExists(GenericContainer<?> container, Shell shell, String proxyName)
      throws IOException;

  /**
   * Create a new proxy via the API.
   *
   * @param container container
   * @param shell shell
   * @param config proxy configuration
   * @throws IOException if creation fails
   */
  void createProxy(GenericContainer<?> container, Shell shell, ProxyConfiguration config)
      throws IOException;

  /**
   * Delete a proxy via the API.
   *
   * @param container container
   * @param shell shell
   * @param proxyName proxy name
   * @throws IOException if deletion fails
   */
  void deleteProxy(GenericContainer<?> container, Shell shell, String proxyName)
      throws IOException;

  /**
   * Check if a toxic exists on a proxy.
   *
   * @param container container
   * @param shell shell
   * @param proxyName proxy name
   * @param toxicName toxic name
   * @return true if toxic exists
   * @throws IOException if API communication fails
   */
  boolean toxicExists(
      GenericContainer<?> container, Shell shell, String proxyName, String toxicName)
      throws IOException;

  /**
   * Add a toxic to a proxy.
   *
   * @param container container
   * @param shell shell
   * @param proxyName proxy name
   * @param toxicName toxic name
   * @param toxicType toxic type (e.g., "latency", "timeout")
   * @param attributes toxic attributes as JSON string
   * @param toxicity probability (0.0-1.0)
   * @throws IOException if toxic creation fails
   */
  void addToxic(
      GenericContainer<?> container,
      Shell shell,
      String proxyName,
      String toxicName,
      String toxicType,
      String attributes,
      double toxicity)
      throws IOException;
}
