/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle;
import com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycleManager;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;
import com.macstab.chaos.proxy.internal.operations.ProxyOperations;
import com.macstab.chaos.proxy.internal.operations.ProxyOperationsManager;
import com.macstab.chaos.proxy.internal.operations.ToxicOperations;
import com.macstab.chaos.proxy.internal.operations.ToxicOperationsManager;
import com.macstab.chaos.proxy.internal.operations.toxic.ToxicConfig;
import com.macstab.chaos.proxy.network.NetworkRedirect;
import com.macstab.chaos.proxy.network.NetworkRedirectManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates Toxiproxy operations across lifecycle, proxy, and toxic managers.
 *
 * <p>This is the single point where {@link ContainerContext} is created. It is resolved exactly
 * once per public operation and passed to all downstream managers — eliminating repeated
 * platform detection across the call chain.
 *
 * <pre>
 * ToxiproxyOrchestrator  ← creates ContainerContext once
 *     ├── ToxiproxyLifecycleManager  (receives ctx)
 *     ├── ProxyOperationsManager     (receives ctx)
 *     ├── ToxicOperationsManager     (receives ctx)
 *     └── NetworkRedirectManager     (receives ctx)
 * </pre>
 *
 * <p><strong>INTERNAL USE ONLY</strong> — implementation detail, not part of the public API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyOrchestrator {

  private final ToxiproxyLifecycle lifecycle;
  private final ProxyOperations proxyOps;
  private final ToxicOperations toxicOps;
  private final NetworkRedirect networkRedirect;

  /** Create orchestrator with default configuration. */
  public ToxiproxyOrchestrator() {
    this(ToxiproxyConfig.defaults());
  }

  /**
   * Create orchestrator with custom configuration.
   *
   * @param config Toxiproxy configuration
   */
  public ToxiproxyOrchestrator(final ToxiproxyConfig config) {
    Objects.requireNonNull(config, "config must not be null");
    this.lifecycle = new ToxiproxyLifecycleManager(config);
    this.proxyOps = new ProxyOperationsManager(config);
    this.toxicOps = new ToxicOperationsManager(config);
    this.networkRedirect = new NetworkRedirectManager();
  }

  /**
   * Create orchestrator with custom components (for testing).
   *
   * @param lifecycle lifecycle manager
   * @param proxyOps proxy operations
   * @param toxicOps toxic operations
   * @param networkRedirect network redirect
   */
  public ToxiproxyOrchestrator(
      final ToxiproxyLifecycle lifecycle,
      final ProxyOperations proxyOps,
      final ToxicOperations toxicOps,
      final NetworkRedirect networkRedirect) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    this.proxyOps = Objects.requireNonNull(proxyOps, "proxyOps must not be null");
    this.toxicOps = Objects.requireNonNull(toxicOps, "toxicOps must not be null");
    this.networkRedirect =
        Objects.requireNonNull(networkRedirect, "networkRedirect must not be null");
  }

  /**
   * Create a proxy for a TCP service.
   *
   * <p>Platform is detected once here and passed to lifecycle and proxy operations.
   *
   * @param container container
   * @param proxyConfig proxy configuration
   * @return proxy configuration
   * @throws ChaosOperationFailedException if proxy creation fails
   */
  public ProxyConfiguration createProxy(
      final GenericContainer<?> container, final ProxyConfiguration proxyConfig) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyConfig, "proxyConfig must not be null");

    try {
      final ContainerContext ctx = ContainerContext.of(container);
      lifecycle.ensureRunning(ctx);
      return proxyOps.createProxy(ctx, proxyConfig);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to create proxy", e);
    }
  }

  /**
   * Add a typed toxic to a proxy.
   *
   * <p>Platform is detected once here and passed to lifecycle and toxic operations.
   *
   * @param container container
   * @param proxyName proxy name
   * @param toxicConfig type-safe toxic configuration
   * @throws ChaosOperationFailedException if toxic addition fails
   */
  public void addToxic(
      final GenericContainer<?> container,
      final String proxyName,
      final ToxicConfig toxicConfig) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicConfig, "toxicConfig must not be null");

    try {
      final ContainerContext ctx = ContainerContext.of(container);
      lifecycle.ensureRunning(ctx);
      toxicOps.addToxic(ctx, proxyName, toxicConfig);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to add toxic", e);
    }
  }

  /**
   * Remove a specific toxic from a proxy.
   *
   * @param container container
   * @param proxyName proxy name
   * @param toxicName toxic name
   * @throws ChaosOperationFailedException if removal fails
   */
  public void removeToxic(
      final GenericContainer<?> container,
      final String proxyName,
      final String toxicName) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");

    try {
      final ContainerContext ctx = ContainerContext.of(container);
      toxicOps.removeToxic(ctx, proxyName, toxicName);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to remove toxic", e);
    }
  }

  /**
   * Remove all toxics from a proxy.
   *
   * @param container container
   * @param proxyName proxy name
   * @throws ChaosOperationFailedException if removal fails
   */
  public void removeAllToxics(final GenericContainer<?> container, final String proxyName) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    try {
      final ContainerContext ctx = ContainerContext.of(container);
      toxicOps.removeAllToxics(ctx, proxyName);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to remove all toxics", e);
    }
  }

  /**
   * Reset all proxy chaos: clear iptables redirects and stop Toxiproxy.
   *
   * @param container container (no-op if not running)
   */
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      final ContainerContext ctx = ContainerContext.of(container);
      networkRedirect.clearAllRedirects(ctx);
      lifecycle.stop(ctx);
      log.info("Reset proxy chaos (stopped Toxiproxy, cleared port redirects)");
    } catch (final Exception e) {
      log.warn("Failed to fully reset proxy chaos", e);
    }
  }
}
