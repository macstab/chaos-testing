/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.proxy.internal.operations.ProxyOperations;
import com.macstab.chaos.proxy.internal.operations.ProxyOperationsManager;
import com.macstab.chaos.proxy.internal.operations.ToxicOperations;
import com.macstab.chaos.proxy.internal.operations.ToxicOperationsManager;
import com.macstab.chaos.toxiproxy.config.ProxyConfiguration;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycle;
import com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycleManager;
import com.macstab.chaos.toxiproxy.network.NetworkRedirect;
import com.macstab.chaos.toxiproxy.network.NetworkRedirectManager;
import com.macstab.chaos.toxiproxy.toxic.ToxicConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates Toxiproxy operations across lifecycle, proxy, and toxic managers.
 *
 * <p>This is the single point where {@link ContainerContext} is created. It is resolved exactly
 * once per public operation and passed to all downstream managers — eliminating repeated platform
 * detection across the call chain.
 *
 * <pre>
 * ToxiproxyOrchestrator  ← creates ContainerContext once
 *     ├── ToxiproxyLifecycleManager  (receives ctx)
 *     ├── ProxyOperationsManager     (receives ctx)
 *     ├── ToxicOperationsManager     (receives ctx)
 *     └── NetworkRedirectManager     (receives ctx)
 * </pre>
 *
 * <h2>⚠️ Shared Toxiproxy Instance — Lifecycle Ownership</h2>
 *
 * <p>A single Toxiproxy process serves <strong>all proxies</strong> inside a container. Multiple
 * modules (cache, network, custom) can create their own proxies on the same Toxiproxy instance.
 * This means:
 *
 * <ul>
 *   <li>{@link #deleteProxy} removes <strong>one</strong> proxy and its iptables rule. The
 *       Toxiproxy process and all other proxies stay alive. Use this for module-level cleanup.
 *   <li>{@link #reset} is <strong>nuclear</strong>: it kills the Toxiproxy process and flushes
 *       <strong>all</strong> iptables rules. Every proxy created by every module is destroyed. Use
 *       this only in {@code @AfterAll} when the container itself is being destroyed.
 * </ul>
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

  /**
   * Registry of active proxies keyed by proxy name.
   *
   * <p>Tracks the {@link ProxyConfiguration} (including service port and proxy port) so that {@link
   * #deleteProxy} can remove the correct iptables rule without a full flush. Entries are added by
   * {@link #createProxy} and removed by {@link #deleteProxy} or {@link #reset}.
   */
  private final Map<String, ProxyConfiguration> activeProxies = new ConcurrentHashMap<>();

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
   * <p>Platform is detected once here and passed to lifecycle and proxy operations. The created
   * proxy is registered in the internal proxy registry for later cleanup via {@link #deleteProxy}.
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
      final ProxyConfiguration result = proxyOps.createProxy(ctx, proxyConfig);
      activeProxies.put(proxyConfig.proxyName(), proxyConfig);
      return result;
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to create proxy", e);
    }
  }

  /**
   * Delete a single proxy and its iptables redirect rule.
   *
   * <p>This is the <strong>safe, targeted cleanup</strong> method. It removes only the named proxy
   * and the corresponding iptables rule — all other proxies and the Toxiproxy process itself remain
   * active. Use this for module-level cleanup (e.g., cache module resetting its Redis proxy without
   * affecting a PostgreSQL proxy created by another module).
   *
   * <p>If the proxy was not registered via {@link #createProxy} on this orchestrator instance, only
   * the Toxiproxy API entry is deleted (the iptables rule cannot be removed without port
   * information).
   *
   * @param container container
   * @param proxyName name of the proxy to delete
   * @throws ChaosOperationFailedException if deletion fails
   */
  public void deleteProxy(final GenericContainer<?> container, final String proxyName) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    try {
      final ContainerContext ctx = ContainerContext.of(container);

      // Remove the specific iptables rule if we know the ports
      final ProxyConfiguration config = activeProxies.remove(proxyName);
      if (config != null) {
        networkRedirect.removeRedirect(ctx, config.servicePort(), config.proxyPort());
      }

      // Delete proxy from Toxiproxy API (removes all its toxics automatically)
      proxyOps.deleteProxy(ctx, proxyName);

      log.info("Deleted proxy '{}' and its iptables rule", proxyName);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to delete proxy: " + proxyName, e);
    }
  }

  /**
   * Add a typed toxic to a proxy.
   *
   * @param container container
   * @param proxyName proxy name
   * @param toxicConfig type-safe toxic configuration
   * @throws ChaosOperationFailedException if toxic addition fails
   */
  public void addToxic(
      final GenericContainer<?> container, final String proxyName, final ToxicConfig toxicConfig) {

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
      final GenericContainer<?> container, final String proxyName, final String toxicName) {

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
   * ⚠️ <strong>NUCLEAR RESET</strong> — destroys ALL proxies and stops Toxiproxy.
   *
   * <p>This method kills the Toxiproxy process and flushes <strong>all</strong> iptables nat rules
   * inside the container. Every proxy created by <em>every module</em> is destroyed — not just
   * proxies registered on this orchestrator instance.
   *
   * <h3>When to Use</h3>
   *
   * <ul>
   *   <li>{@code @AfterAll} — when the container is being destroyed anyway
   *   <li>When you are the <strong>sole user</strong> of Toxiproxy in this container
   *   <li>When you need to guarantee a completely clean state
   * </ul>
   *
   * <h3>When NOT to Use</h3>
   *
   * <ul>
   *   <li>{@code @AfterEach} with multiple modules sharing the same container
   *   <li>When another module's proxy must survive your cleanup
   * </ul>
   *
   * <p>For targeted cleanup of a single proxy, use {@link #deleteProxy} instead.
   *
   * @param container container (no-op if not running)
   */
  /**
   * Surgically removes all proxies owned by this orchestrator instance, without affecting
   * proxies created by other modules (connection, cache) or killing the Toxiproxy process.
   *
   * <p>Iterates {@code activeProxies}, deletes each proxy from Toxiproxy via the API, removes
   * its iptables redirect rule, and clears the tracking map. The Toxiproxy process stays
   * running. Other modules' proxies and iptables rules are untouched.
   *
   * <p>For full container teardown (kill Toxiproxy + flush all iptables), use
   * {@link #shutdown(GenericContainer)}.
   *
   * @param container target container (no-op if not running)
   */
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    final ContainerContext ctx = ContainerContext.of(container);
    int removed = 0;

    for (final var entry : activeProxies.entrySet()) {
      try {
        proxyOps.deleteProxy(ctx, entry.getKey());
        final ProxyConfiguration config = entry.getValue();
        networkRedirect.removeRedirect(ctx, config.servicePort(), config.proxyPort());
        removed++;
      } catch (final Exception e) {
        log.debug("Failed to remove proxy '{}' during reset: {}", entry.getKey(), e.getMessage());
      }
    }
    activeProxies.clear();
    log.info("Reset proxy chaos: removed {} proxies (Toxiproxy still running)", removed);
  }

  /**
   * Terminates the Toxiproxy process and flushes all iptables NAT rules — destroying every
   * proxy from every module on this container.
   *
   * <p><strong>NUCLEAR — use only in {@code @AfterAll}.</strong> This kills the shared Toxiproxy
   * process, severing all TCP connections through all proxies. All iptables redirects are flushed.
   * After this call, any module that still holds proxy references has stale state.
   *
   * @param container target container (no-op if not running)
   */
  public void shutdown(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      final ContainerContext ctx = ContainerContext.of(container);
      lifecycle.shutdown(ctx);
      activeProxies.clear();
      log.info("Shutdown Toxiproxy: killed process + flushed all iptables rules");
    } catch (final Exception e) {
      log.warn("Failed to fully shutdown Toxiproxy", e);
    }
  }
}
