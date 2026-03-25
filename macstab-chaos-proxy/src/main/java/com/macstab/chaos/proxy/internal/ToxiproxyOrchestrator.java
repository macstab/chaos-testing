/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal;

import java.io.IOException;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycle;
import com.macstab.chaos.proxy.internal.lifecycle.ToxiproxyLifecycleManager;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;
import com.macstab.chaos.proxy.internal.operations.ProxyOperations;
import com.macstab.chaos.proxy.internal.operations.ProxyOperationsManager;
import com.macstab.chaos.proxy.internal.operations.ToxicOperations;
import com.macstab.chaos.proxy.internal.operations.ToxicOperationsManager;
import com.macstab.chaos.proxy.internal.operations.toxic.LegacyToxicConfig;
import com.macstab.chaos.proxy.network.NetworkRedirect;
import com.macstab.chaos.proxy.network.NetworkRedirectManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrator for Toxiproxy operations.
 *
 * <p>Coordinates lifecycle, proxy, and toxic operations through dedicated managers. Replaces the
 * old god-class ToxiproxyManager with clean separation of concerns.
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * ToxiproxyOrchestrator
 *     ├── ToxiproxyLifecycleManager (start/stop/health)
 *     ├── ProxyOperationsManager (proxy CRUD)
 *     ├── ToxicOperationsManager (toxic CRUD)
 *     └── NetworkRedirectManager (iptables)
 * </pre>
 *
 * <p><strong>INTERNAL USE ONLY</strong> - Implementation detail, not part of public API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyOrchestrator {

  private final ToxiproxyConfig config;
  private final ToxiproxyLifecycle lifecycle;
  private final ProxyOperations proxyOps;
  private final ToxicOperations toxicOps;
  private final NetworkRedirect networkRedirect;

  /** Create orchestrator with default configuration and components. */
  public ToxiproxyOrchestrator() {
    this(ToxiproxyConfig.defaults());
  }

  /**
   * Create orchestrator with custom configuration.
   *
   * @param config Toxiproxy configuration
   */
  public ToxiproxyOrchestrator(final ToxiproxyConfig config) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.lifecycle = new ToxiproxyLifecycleManager(config);
    this.proxyOps = new ProxyOperationsManager(config);
    this.toxicOps = new ToxicOperationsManager(config);
    this.networkRedirect = new NetworkRedirectManager();
  }

  /**
   * Create a proxy for a TCP service.
   *
   * <p>Ensures Toxiproxy is running, creates the proxy, and sets up network redirection.
   *
   * @param container container
   * @param proxyConfig proxy configuration
   * @return proxy configuration
   */
  public ProxyConfiguration createProxy(
      final GenericContainer<?> container, final ProxyConfiguration proxyConfig) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyConfig, "proxyConfig must not be null");

    try {
      lifecycle.ensureRunning(container);
      return proxyOps.createProxy(container, proxyConfig);

    } catch (final Exception e) {
      throw handleProxyError("Failed to create proxy", e);
    }
  }

  /**
   * Add a toxic to a proxy.
   *
   * <p>Ensures Toxiproxy is running, then adds the toxic.
   *
   * @param container container
   * @param proxyName proxy name
   * @param toxicName toxic name
   * @param toxicType toxic type
   * @param attributes toxic attributes (JSON)
   * @param toxicity probability (0.0-1.0)
   */
  public void addToxic(
      final GenericContainer<?> container,
      final String proxyName,
      final String toxicName,
      final String toxicType,
      final String attributes,
      final double toxicity) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");
    Objects.requireNonNull(toxicType, "toxicType must not be null");

    validateToxicity(toxicity);

    try {
      lifecycle.ensureRunning(container);

      final LegacyToxicConfig toxic =
          new LegacyToxicConfig(toxicName, toxicType, attributes, toxicity);
      toxicOps.addToxic(container, proxyName, toxic);

    } catch (final Exception e) {
      throw handleToxicError("Failed to add toxic", e);
    }
  }

  /**
   * Reset all proxy chaos (stop Toxiproxy, clear redirects).
   *
   * @param container container
   */
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      final Platform platform = PlatformDetector.detect(container);
      final Shell shell = platform.getDefaultShell();

      networkRedirect.clearAllRedirects(container, shell);
      lifecycle.stop(container);

      log.info("Reset proxy chaos (stopped Toxiproxy, removed port redirects)");

    } catch (final Exception e) {
      log.warn("Failed to fully reset proxy chaos", e);
    }
  }

  // ==================== Private Helpers ====================

  /**
   * Validate toxicity is in valid range.
   *
   * @param toxicity toxicity value
   * @throws IllegalArgumentException if out of range
   */
  private void validateToxicity(final double toxicity) {
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  /**
   * Handle proxy operation error.
   *
   * @param message error message
   * @param e exception
   * @return ChaosOperationFailedException
   */
  private ChaosOperationFailedException handleProxyError(final String message, final Exception e) {
    if (e instanceof ChaosOperationFailedException) {
      return (ChaosOperationFailedException) e;
    }
    if (e instanceof IOException) {
      return new ChaosOperationFailedException(message, e);
    }
    return new ChaosOperationFailedException(message, e);
  }

  /**
   * Handle toxic operation error.
   *
   * @param message error message
   * @param e exception
   * @return ChaosOperationFailedException
   */
  private ChaosOperationFailedException handleToxicError(final String message, final Exception e) {
    if (e instanceof ChaosOperationFailedException) {
      return (ChaosOperationFailedException) e;
    }
    if (e instanceof IOException) {
      return new ChaosOperationFailedException(message, e);
    }
    return new ChaosOperationFailedException(message, e);
  }
}
