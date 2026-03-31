/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations;

import java.io.IOException;

import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.config.ProxyConfiguration;

/**
 * Proxy CRUD operations against the Toxiproxy REST API.
 *
 * <p>A <em>proxy</em> in Toxiproxy terminology is a named TCP intercept that listens on a {@code
 * proxyPort} and forwards traffic to a {@code servicePort} on {@code localhost} — with any active
 * toxics applied in between. This interface covers creating, checking, and deleting proxy entries,
 * plus setting up the iptables port redirect that makes the interception transparent to clients.
 *
 * <h2>Transparent Interception Architecture</h2>
 *
 * <pre>
 * Client → container-hostname:servicePort
 *              ↓  (iptables PREROUTING / OUTPUT)
 *         localhost:proxyPort  (Toxiproxy)
 *              ↓  (toxics applied here)
 *         localhost:servicePort  (real service)
 * </pre>
 *
 * <h2>Context Passing</h2>
 *
 * <p>All methods receive a pre-resolved {@link ContainerContext}. Platform detection is performed
 * exactly once per entry point in {@link com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator}
 * and passed through the entire call chain.
 *
 * <h2>Default Implementation</h2>
 *
 * <p>{@link ProxyOperationsManager} is the production implementation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ProxyOperations {

  /**
   * Create a new proxy for a TCP service and set up transparent port redirection.
   *
   * <p>This method is idempotent: if a proxy with the same name already exists and its port is
   * listening, it returns the existing configuration without any changes. If the proxy exists in
   * the API but its port is not listening (broken state), it is deleted and recreated.
   *
   * <p>Creation sequence:
   *
   * <ol>
   *   <li>Check if proxy exists in Toxiproxy API and its port is listening.
   *   <li>If broken (exists but not listening): delete and recreate.
   *   <li>Create proxy via {@code POST /proxies}.
   *   <li>Set up iptables redirect: servicePort → proxyPort.
   *   <li>Poll until proxyPort is listening (or timeout).
   * </ol>
   *
   * @param ctx resolved container context (container must be running)
   * @param config proxy configuration including name, ports, and container hostname
   * @return the proxy configuration (same as input after successful creation)
   * @throws IOException if proxy creation, iptables setup, or readiness poll fails
   */
  ProxyConfiguration createProxy(ContainerContext ctx, ProxyConfiguration config)
      throws IOException;

  /**
   * Delete a proxy entry from the Toxiproxy API.
   *
   * <p>Removes the proxy via {@code DELETE /proxies/{proxyName}}. Any active toxics on this proxy
   * are also removed (Toxiproxy deletes them automatically). The iptables redirect is <em>not</em>
   * removed by this method — use {@link #deleteAllProxies(ContainerContext)} or {@link
   * com.macstab.chaos.proxy.network.NetworkRedirect#clearAllRedirects(ContainerContext)} to clean
   * up port redirects.
   *
   * @param ctx resolved container context
   * @param proxyName name of the proxy to delete
   * @throws IOException if the Toxiproxy API call fails
   */
  void deleteProxy(ContainerContext ctx, String proxyName) throws IOException;

  /**
   * Check whether a named proxy exists in the Toxiproxy API.
   *
   * <p>Issues {@code GET /proxies/{proxyName}}. Returns {@code false} on any error — API
   * unreachable, proxy not found, container stopped — and never throws. Safe to use in assertions
   * and polling loops.
   *
   * <p>Note: a {@code true} return means the proxy is registered in Toxiproxy's configuration. It
   * does <em>not</em> guarantee the proxy port is actively listening. Use in combination with a
   * port check for full readiness validation.
   *
   * @param ctx resolved container context
   * @param proxyName proxy name to check
   * @return {@code true} if the proxy exists, {@code false} otherwise (including on error)
   */
  boolean proxyExists(ContainerContext ctx, String proxyName);

  /**
   * Clear all iptables port redirects for this container.
   *
   * <p>Flushes the iptables chains used for transparent port redirection. Does not delete proxy
   * entries from the Toxiproxy API — use {@link #deleteProxy(ContainerContext, String)} for that.
   * Typically called as part of a full reset sequence in {@link
   * com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator#reset}.
   *
   * @param ctx resolved container context
   * @throws IOException if the iptables flush command fails
   */
  void deleteAllProxies(ContainerContext ctx) throws IOException;
}
