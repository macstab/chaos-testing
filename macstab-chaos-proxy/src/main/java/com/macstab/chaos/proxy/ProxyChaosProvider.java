/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy;

import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ProxyChaos;
import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;

/**
 * Provider for universal TCP proxy-based chaos injection.
 *
 * <p>Enables application-level fault injection for any TCP service without modifying the service
 * itself. Supports latency, timeouts, bandwidth limits, connection issues, and more.
 *
 * <p><strong>Architecture:</strong> Uses iptables PREROUTING to transparently redirect traffic.
 * Clients must connect via container hostname (not localhost) to hit the proxy.
 *
 * <p><strong>Example usage:</strong>
 *
 * <pre>{@code
 * ProxyChaos chaos = new ProxyChaosProvider();
 *
 * // Create proxy for Redis
 * String hostname = chaos.createProxy(container, "redis", 6379, 16379);
 *
 * // Add 200ms latency
 * chaos.addLatency(container, "redis", Duration.ofMillis(200));
 *
 * // Connect using hostname (not localhost!)
 * Jedis client = new Jedis(hostname, 6379);
 *
 * // Cleanup
 * chaos.reset(container);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ProxyChaosProvider implements ProxyChaos {

  private final ToxiproxyOrchestrator orchestrator;

  /** Create provider with default configuration. */
  public ProxyChaosProvider() {
    this(ToxiproxyConfig.defaults());
  }

  /**
   * Create provider with custom configuration.
   *
   * @param config Toxiproxy configuration
   */
  public ProxyChaosProvider(final ToxiproxyConfig config) {
    this.orchestrator = new ToxiproxyOrchestrator(config);
  }

  @Override
  public String createProxy(
      final GenericContainer<?> container,
      final String proxyName,
      final int servicePort,
      final int proxyPort) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    final String hostname = getContainerHostname(container);
    final ProxyConfiguration config =
        new ProxyConfiguration(proxyName, servicePort, proxyPort, hostname);

    orchestrator.createProxy(container, config);

    return hostname;
  }

  @Override
  public void addLatency(
      final GenericContainer<?> container, final String proxyName, final Duration latency) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(latency, "latency must not be null");

    if (latency.isNegative()) {
      throw new IllegalArgumentException("latency must not be negative");
    }

    orchestrator.addToxic(
        container,
        proxyName,
        "latency",
        "latency",
        String.format("{\"latency\":%d}", latency.toMillis()),
        1.0);
  }

  @Override
  public void addTimeout(
      final GenericContainer<?> container,
      final String proxyName,
      final Duration timeout,
      final double probability) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");

    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException(
          "timeout must be positive (use Duration.ofMillis(1) for instant close)");
    }

    if (probability < 0.0 || probability > 1.0) {
      throw new IllegalArgumentException(
          String.format("probability must be in [0.0, 1.0], got: %.2f", probability));
    }

    orchestrator.addToxic(
        container,
        proxyName,
        "timeout",
        "timeout",
        String.format("{\"timeout\":%d}", timeout.toMillis()),
        probability);
  }

  @Override
  public void limitBandwidth(
      final GenericContainer<?> container, final String proxyName, final long rateKBps) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    if (rateKBps <= 0) {
      throw new IllegalArgumentException("rateKBps must be positive");
    }

    orchestrator.addToxic(
        container,
        proxyName,
        "bandwidth",
        "bandwidth",
        String.format("{\"rate\":%d}", rateKBps * 1024), // Convert KB/s to bytes/s
        1.0);
  }

  @Override
  public void slowClose(
      final GenericContainer<?> container, final String proxyName, final Duration delay) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(delay, "delay must not be null");

    if (delay.isNegative()) {
      throw new IllegalArgumentException("delay must not be negative");
    }

    orchestrator.addToxic(
        container,
        proxyName,
        "slow_close",
        "slow_close",
        String.format("{\"delay\":%d}", delay.toMillis()),
        1.0);
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    orchestrator.reset(container);
  }

  @Override
  public boolean isSupported() {
    return true; // Proxy chaos works on all Linux containers
  }

  private String getContainerHostname(final GenericContainer<?> container) {
    try {
      final var result = container.execInContainer("hostname");
      if (result.getExitCode() != 0) {
        throw new IllegalStateException("Failed to get container hostname: " + result.getStderr());
      }
      return result.getStdout().trim();
    } catch (final Exception e) {
      throw new IllegalStateException("Failed to get container hostname", e);
    }
  }
}
