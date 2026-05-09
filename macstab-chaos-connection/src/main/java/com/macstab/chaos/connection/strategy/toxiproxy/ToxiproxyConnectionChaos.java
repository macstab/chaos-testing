/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.strategy.toxiproxy;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.spi.ConnectionChaosStrategy;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient;
import com.macstab.chaos.toxiproxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.toxiproxy.config.ProxyConfiguration;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycle;
import com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycleManager;
import com.macstab.chaos.toxiproxy.network.NetworkRedirect;
import com.macstab.chaos.toxiproxy.network.NetworkRedirectManager;
import com.macstab.chaos.toxiproxy.toxic.BandwidthToxic;
import com.macstab.chaos.toxiproxy.toxic.DownToxic;
import com.macstab.chaos.toxiproxy.toxic.LatencyToxic;
import com.macstab.chaos.toxiproxy.toxic.LimitDataToxic;
import com.macstab.chaos.toxiproxy.toxic.SlowCloseToxic;
import com.macstab.chaos.toxiproxy.toxic.TimeoutToxic;
import com.macstab.chaos.toxiproxy.toxic.ToxicConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Connection chaos using shared Toxiproxy infrastructure from {@code macstab-chaos-toxiproxy-core}.
 *
 * <p>Targets outbound connections from a container to external services via {@code "host:port"}
 * address strings. Uses the shared Toxiproxy lifecycle — safe to use alongside {@link
 * com.macstab.chaos.core.api.ProxyChaos} on the same container.
 *
 * <p><strong>REQUIRES NET_ADMIN CAPABILITY</strong> for iptables redirect.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class ToxiproxyConnectionChaos implements ConnectionChaosStrategy {

  private static final int PROXY_PORT_OFFSET = 10000;
  private static final int MAX_PORT = 65535;
  private static final Pattern VALID_HOSTNAME = Pattern.compile("^[a-zA-Z0-9._-]+$");

  private final ToxiproxyConfig config;
  private final ToxiproxyLifecycle lifecycle;
  private final ToxiproxyApiClient apiClient;
  private final NetworkRedirect networkRedirect;

  /** Tracks proxies this module created — only these are removed on reset. */
  private final Map<String, ProxyConfiguration> ownedProxies = new ConcurrentHashMap<>();

  /** Creates connection chaos with default configuration. */
  public ToxiproxyConnectionChaos() {
    this(ToxiproxyConfig.defaults());
  }

  /** Creates connection chaos with custom configuration. */
  public ToxiproxyConnectionChaos(final ToxiproxyConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.lifecycle = new ToxiproxyLifecycleManager(config);
    this.apiClient = new ToxiproxyApiClientImpl(config.apiUrl());
    this.networkRedirect = new NetworkRedirectManager();
  }

  /**
   * Package-private constructor for unit testing — accepts collaborator mocks/stubs.
   *
   * @param config Toxiproxy configuration
   * @param lifecycle lifecycle manager (mock in tests)
   * @param apiClient Toxiproxy API client (mock in tests)
   * @param networkRedirect network redirect manager (mock in tests)
   */
  ToxiproxyConnectionChaos(
      final ToxiproxyConfig config,
      final ToxiproxyLifecycle lifecycle,
      final ToxiproxyApiClient apiClient,
      final NetworkRedirect networkRedirect) {
    this.config = Objects.requireNonNull(config, "config");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
    this.networkRedirect = Objects.requireNonNull(networkRedirect, "networkRedirect");
  }

  @Override
  public void addLatency(
      final GenericContainer<?> container, final String target, final Duration latency) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(latency, "latency must not be null");
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ensureProxyFor(container, addr);

    final LatencyToxic toxic =
        LatencyToxic.builder().name("latency").latencyMs((int) latency.toMillis()).build();

    addToxicSafe(ctx, proxyName(addr), toxic, target, "latency");
    log.info("Added {}ms latency to {}", latency.toMillis(), target);
  }

  @Override
  public void dropPackets(
      final GenericContainer<?> container, final String target, final double rate) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    if (rate < 0.0 || rate > 1.0) {
      throw new IllegalArgumentException(
          String.format("rate must be in [0.0, 1.0], got: %.2f", rate));
    }
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ensureProxyFor(container, addr);

    final DownToxic toxic = DownToxic.builder().name("down").toxicity(rate).build();

    addToxicSafe(ctx, proxyName(addr), toxic, target, "packet drop");
    log.info("Added {:.0%} packet loss to {}", rate, target);
  }

  @Override
  public void limitBandwidth(
      final GenericContainer<?> container, final String target, final long bytesPerSecond) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    if (bytesPerSecond < 1) {
      throw new IllegalArgumentException(
          String.format("bytesPerSecond must be >= 1, got: %d", bytesPerSecond));
    }
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ensureProxyFor(container, addr);

    final BandwidthToxic toxic =
        BandwidthToxic.builder().name("bandwidth").rateKbps((int) (bytesPerSecond / 1024)).build();

    addToxicSafe(ctx, proxyName(addr), toxic, target, "bandwidth limit");
    log.info("Limited bandwidth to {} bytes/s for {}", bytesPerSecond, target);
  }

  @Override
  public void timeoutConnections(
      final GenericContainer<?> container, final String target, final Duration timeout) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ensureProxyFor(container, addr);

    final TimeoutToxic toxic =
        TimeoutToxic.builder().name("timeout").timeoutMs((int) timeout.toMillis()).build();

    addToxicSafe(ctx, proxyName(addr), toxic, target, "timeout");
    log.info("Added {}ms timeout to {}", timeout.toMillis(), target);
  }

  @Override
  public void slowClose(
      final GenericContainer<?> container, final String target, final Duration delay) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(delay, "delay must not be null");
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ensureProxyFor(container, addr);

    final SlowCloseToxic toxic =
        SlowCloseToxic.builder().name("slow_close").delayMs((int) delay.toMillis()).build();

    addToxicSafe(ctx, proxyName(addr), toxic, target, "slow close");
    log.info("Added {}ms slow close to {}", delay.toMillis(), target);
  }

  @Override
  public void rejectConnections(final GenericContainer<?> container, final String target) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ensureProxyFor(container, addr);

    // Use DownToxic(toxicity=1.0) — drops all data without closing the connection.
    // This is atomic: no delete/recreate race window. All new connections are silently dropped.
    final DownToxic toxic = DownToxic.builder().name("reject").toxicity(1.0).build();
    addToxicSafe(ctx, proxyName(addr), toxic, target, "reject connections");
    log.info("Rejecting all connections to {}", target);
  }

  /**
   * Truncate connection after {@code bytes} cumulative bytes transmitted.
   *
   * <p>Simulates mid-stream disconnect — client receives a partial response and must handle {@code
   * SocketException} / {@code EOFException}. Tests reconnection logic and partial-read resilience.
   *
   * @param container target container (must be running)
   * @param target target host:port
   * @param bytes byte threshold (0 = disconnect immediately on first data)
   * @throws IllegalArgumentException if bytes is negative
   */
  public void truncateConnection(
      final GenericContainer<?> container, final String target, final long bytes) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must be >= 0, got: " + bytes);
    }
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ensureProxyFor(container, addr);

    final LimitDataToxic toxic = LimitDataToxic.builder().name("limit_data").bytes(bytes).build();
    addToxicSafe(ctx, proxyName(addr), toxic, target, "truncate connection");
    log.info("Truncating connection to {} after {} bytes", target, bytes);
  }

  /**
   * Add latency with jitter — realistic network simulation.
   *
   * <p>Actual per-chunk delay varies uniformly in {@code [latency - jitter, latency + jitter]}.
   * More realistic than fixed latency for simulating real-world network variability.
   *
   * @param container target container (must be running)
   * @param target target host:port
   * @param latency base delay
   * @param jitter jitter amplitude (must be ≥ 0)
   */
  public void addLatencyWithJitter(
      final GenericContainer<?> container,
      final String target,
      final Duration latency,
      final Duration jitter) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(latency, "latency must not be null");
    Objects.requireNonNull(jitter, "jitter must not be null");
    if (jitter.isNegative()) {
      throw new IllegalArgumentException("jitter must be >= 0, got: " + jitter);
    }
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ensureProxyFor(container, addr);

    final LatencyToxic toxic =
        LatencyToxic.builder()
            .name("latency")
            .latencyMs((int) latency.toMillis())
            .jitterMs((int) jitter.toMillis())
            .build();
    addToxicSafe(ctx, proxyName(addr), toxic, target, "latency with jitter");
    log.info(
        "Added {}ms ±{}ms jitter latency to {}", latency.toMillis(), jitter.toMillis(), target);
  }

  /**
   * Remove a specific toxic from a target's proxy by name.
   *
   * <p>No-op if the toxic does not exist.
   *
   * @param container target container (must be running)
   * @param target target host:port
   * @param toxicName name of the toxic to remove (e.g., "latency", "down")
   */
  @Override
  public void removeToxic(
      final GenericContainer<?> container, final String target, final String toxicName) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ContainerContext.of(container);

    try {
      apiClient.deleteToxic(ctx, proxyName(addr), toxicName);
      log.info("Removed toxic '{}' from {}", toxicName, target);
    } catch (final Exception e) {
      log.debug(
          "removeToxic '{}' on {} — not found or already removed: {}",
          toxicName,
          target,
          e.getMessage());
    }
  }

  /**
   * Remove all toxics from a target's proxy, restoring it to clean pass-through.
   *
   * <p>The proxy itself stays active — only faults are removed. Use this for per-test cleanup when
   * you want to reuse the proxy across test methods.
   *
   * @param container target container (must be running)
   * @param target target host:port
   */
  @Override
  public void removeAllToxics(final GenericContainer<?> container, final String target) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    validateRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final ContainerContext ctx = ContainerContext.of(container);

    try {
      for (final String name : apiClient.listToxics(ctx, proxyName(addr))) {
        try {
          apiClient.deleteToxic(ctx, proxyName(addr), name);
        } catch (final Exception e) {
          log.debug("Failed to remove toxic '{}' from {}: {}", name, target, e.getMessage());
        }
      }
      log.info("Removed all toxics from {}", target);
    } catch (final Exception e) {
      log.debug("removeAllToxics on {} — proxy may not exist: {}", target, e.getMessage());
    }
  }

  /**
   * Reset chaos for a single target only — surgical cleanup without disturbing other targets.
   *
   * <p>Removes the proxy and iptables rule for {@code target} only. All other targets and the
   * Toxiproxy process itself remain active.
   *
   * @param container target container (must be running)
   * @param target target host:port to reset
   */
  public void reset(final GenericContainer<?> container, final String target) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    if (!container.isRunning()) {
      return;
    }

    final TargetAddress addr = TargetAddress.parse(target);
    final String name = proxyName(addr);
    final ProxyConfiguration proxyConfig = ownedProxies.remove(name);
    if (proxyConfig == null) {
      return; // not owned by this instance
    }

    final ContainerContext ctx = ContainerContext.of(container);
    try {
      apiClient.deleteProxy(ctx, name);
      networkRedirect.removeRedirect(ctx, proxyConfig.servicePort(), proxyConfig.proxyPort());
      log.info("Reset connection chaos for target {}", target);
    } catch (final Exception e) {
      log.debug("Failed to reset target {}: {}", target, e.getMessage());
    }
  }

  /** Removes only proxies created by this module — safe for concurrent use with proxy module. */
  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!container.isRunning()) {
      return;
    }

    final ContainerContext ctx = ContainerContext.of(container);

    for (final var entry : ownedProxies.entrySet()) {
      try {
        apiClient.deleteProxy(ctx, entry.getKey());
        final ProxyConfiguration proxyConfig = entry.getValue();
        networkRedirect.removeRedirect(ctx, proxyConfig.servicePort(), proxyConfig.proxyPort());
        log.debug("Removed connection proxy: {}", entry.getKey());
      } catch (final Exception e) {
        log.debug("Failed to remove proxy {} during reset: {}", entry.getKey(), e.getMessage());
      }
    }
    final int removed = ownedProxies.size();
    ownedProxies.clear();
    log.info("Reset connection chaos (removed {} proxies)", removed);
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  /**
   * Always {@code true} for a running container — this strategy can install Toxiproxy on demand
   * regardless of the container's prior state.
   *
   * @param container target container
   * @return {@code true} when {@code container.isRunning()}
   * @throws NullPointerException if {@code container} is null
   */
  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.isRunning();
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    final ContainerContext ctx = ContainerContext.of(container);
    try {
      lifecycle.ensureRunning(ctx);
    } catch (final java.io.IOException e) {
      throw new ChaosOperationFailedException("Failed to start Toxiproxy", e);
    }
  }

  // ==================== Internal ====================

  private ContainerContext ensureProxyFor(
      final GenericContainer<?> container, final TargetAddress addr) {
    final ContainerContext ctx = ContainerContext.of(container);
    try {
      lifecycle.ensureRunning(ctx);
    } catch (final java.io.IOException e) {
      throw new ChaosOperationFailedException("Failed to start Toxiproxy", e);
    }

    final String name = proxyName(addr);
    if (ownedProxies.containsKey(name)) {
      return ctx;
    }

    final int proxyPort = allocateProxyPort(addr.port());
    final ProxyConfiguration proxyConfig =
        new ProxyConfiguration(name, addr.port(), proxyPort, addr.host());

    try {
      if (!apiClient.proxyExists(ctx, name)) {
        apiClient.createProxy(ctx, proxyConfig);
        networkRedirect.setupRedirect(ctx, addr.port(), proxyPort);
      }
      ownedProxies.put(name, proxyConfig);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "Failed to create proxy for " + addr.host() + ":" + addr.port(), e);
    }
    return ctx;
  }

  private void addToxicSafe(
      final ContainerContext ctx,
      final String proxyName,
      final ToxicConfig toxic,
      final String target,
      final String operation) {
    try {
      apiClient.addToxic(
          ctx, proxyName, toxic.name(), toxic.type(), toxic.toJson(), toxic.toxicity());
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to add " + operation + " to " + target, e);
    }
  }

  private static int allocateProxyPort(final int targetPort) {
    final int proxyPort = PROXY_PORT_OFFSET + targetPort;
    if (proxyPort > MAX_PORT) {
      throw new ChaosOperationFailedException(
          String.format("Proxy port %d exceeds max port %d", proxyPort, MAX_PORT));
    }
    return proxyPort;
  }

  private static String proxyName(final TargetAddress addr) {
    return "conn_" + addr.host().replace(".", "_") + "_" + addr.port();
  }

  private static void validateRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }

  /** Validated target address (host:port). */
  record TargetAddress(String host, int port) {

    TargetAddress {
      Objects.requireNonNull(host, "host must not be null");
      if (host.isEmpty()) {
        throw new IllegalArgumentException("host must not be empty");
      }
      if (!VALID_HOSTNAME.matcher(host).matches()) {
        throw new IllegalArgumentException("Invalid hostname format: " + host);
      }
      if (port < 1 || port > MAX_PORT) {
        throw new IllegalArgumentException(
            String.format("port must be 1-%d, got: %d", MAX_PORT, port));
      }
    }

    static TargetAddress parse(final String target) {
      Objects.requireNonNull(target, "target must not be null");
      final String[] parts = target.split(":");
      if (parts.length != 2) {
        throw new IllegalArgumentException("target must be host:port format, got: " + target);
      }
      try {
        return new TargetAddress(parts[0], Integer.parseInt(parts[1]));
      } catch (final NumberFormatException e) {
        throw new IllegalArgumentException("Invalid port in target: " + target, e);
      }
    }
  }
}
