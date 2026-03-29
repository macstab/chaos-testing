/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations;

import java.io.IOException;

import com.macstab.chaos.proxy.internal.ContainerContext;
import com.macstab.chaos.proxy.internal.operations.toxic.ToxicConfig;

/**
 * Toxic CRUD operations against the Toxiproxy REST API.
 *
 * <p>A <em>toxic</em> is a fault injector attached to a named proxy. It intercepts data flowing
 * through the proxy and applies a configurable disruption — latency, bandwidth limit, connection
 * drop, etc. Multiple toxics can be active on the same proxy simultaneously; each is identified by
 * a unique name.
 *
 * <h2>Toxiproxy REST Endpoints Used</h2>
 *
 * <ul>
 *   <li>{@code GET /proxies/{name}/toxics} — list toxics (used by {@link #toxicExists} and {@link
 *       #removeAllToxics})
 *   <li>{@code POST /proxies/{name}/toxics} — add a toxic
 *   <li>{@code DELETE /proxies/{name}/toxics/{toxicName}} — remove a specific toxic
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * <p>{@link #addToxic} is idempotent: if a toxic with the same name already exists on the proxy,
 * the operation is silently skipped. To update an existing toxic, remove it first with {@link
 * #removeToxic} then add the new configuration.
 *
 * <h2>Context Passing</h2>
 *
 * <p>All methods receive a pre-resolved {@link ContainerContext}. Platform detection is performed
 * exactly once per entry point in {@link com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator}.
 *
 * <h2>Default Implementation</h2>
 *
 * <p>{@link ToxicOperationsManager} is the production implementation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ToxicOperations {

  /**
   * Add a typed toxic to a proxy.
   *
   * <p>Idempotent: if a toxic with the same {@link ToxicConfig#name()} already exists on {@code
   * proxyName}, this method logs and returns without modifying the existing toxic.
   *
   * <p>The toxic is added via {@code POST /proxies/{proxyName}/toxics} using the JSON attributes
   * from {@link ToxicConfig#toJson()}.
   *
   * @param ctx resolved container context (container must be running)
   * @param proxyName name of the proxy to attach the toxic to
   * @param config type-safe toxic configuration (name, type, attributes, toxicity)
   * @throws IOException if the Toxiproxy API call fails
   * @throws IllegalStateException if {@code ctx.container().isRunning()} returns {@code false}
   */
  void addToxic(ContainerContext ctx, String proxyName, ToxicConfig config) throws IOException;

  /**
   * Remove a specific toxic from a proxy by name.
   *
   * <p>Issues {@code DELETE /proxies/{proxyName}/toxics/{toxicName}}. The behavior when the toxic
   * does not exist depends on the Toxiproxy server version — it may return an error or succeed
   * silently. Callers should treat non-existent toxics as already removed.
   *
   * @param ctx resolved container context (container must be running)
   * @param proxyName name of the proxy
   * @param toxicName name of the toxic to remove
   * @throws IOException if the Toxiproxy API call fails
   * @throws IllegalStateException if {@code ctx.container().isRunning()} returns {@code false}
   */
  void removeToxic(ContainerContext ctx, String proxyName, String toxicName) throws IOException;

  /**
   * Check whether a named toxic exists on a proxy.
   *
   * <p>Fetches the toxic list via {@code GET /proxies/{proxyName}/toxics} and searches for a
   * matching {@code "name"} field. Returns {@code false} on any error — API unreachable, container
   * stopped — and never throws. Safe to use in polling loops.
   *
   * @param ctx resolved container context
   * @param proxyName name of the proxy
   * @param toxicName toxic name to check
   * @return {@code true} if the toxic exists, {@code false} otherwise (including on error)
   */
  boolean toxicExists(ContainerContext ctx, String proxyName, String toxicName);

  /**
   * Remove all toxics from a proxy, leaving it as a clean pass-through.
   *
   * <p>Fetches the current toxic list via {@code GET /proxies/{proxyName}/toxics}, then deletes
   * each one individually via {@code DELETE /proxies/{proxyName}/toxics/{toxicName}}. The proxy
   * itself remains active and continues forwarding traffic without any fault injection.
   *
   * <p>Idempotent: if no toxics exist, this method completes without error.
   *
   * @param ctx resolved container context (container must be running)
   * @param proxyName name of the proxy to clean
   * @throws IOException if the Toxiproxy API list or delete call fails
   * @throws IllegalStateException if {@code ctx.container().isRunning()} returns {@code false}
   */
  void removeAllToxics(ContainerContext ctx, String proxyName) throws IOException;
}
