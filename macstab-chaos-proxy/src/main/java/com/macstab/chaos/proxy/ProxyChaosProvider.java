/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy;

import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ProxyChaos;
import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;
import com.macstab.chaos.proxy.internal.operations.toxic.BandwidthToxic;
import com.macstab.chaos.proxy.internal.operations.toxic.LatencyToxic;
import com.macstab.chaos.proxy.internal.operations.toxic.SlowCloseToxic;
import com.macstab.chaos.proxy.internal.operations.toxic.TimeoutToxic;

/**
 * Provider for universal TCP proxy-based chaos injection.
 *
 * <p>Enables transparent fault injection into any TCP service without modifying application code.
 * Clients connect via the address returned by {@link #createProxy} — traffic routes through
 * Toxiproxy, which applies the configured faults.
 *
 * <p><strong>Example usage:</strong>
 *
 * <pre>{@code
 * ProxyChaos chaos = new ProxyChaosProvider();
 *
 * String hostname = chaos.createProxy(container, "redis", 6379, 16379);
 * chaos.addLatency(container, "redis", Duration.ofMillis(200));
 *
 * Jedis client = new Jedis(hostname, 6379);  // Connect via hostname, NOT localhost
 *
 * // Remove just latency, keep the proxy active
 * chaos.removeToxic(container, "redis", "latency");
 *
 * // Or remove everything and reset
 * chaos.reset(container);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ProxyChaosProvider implements ProxyChaos {

  /** Maximum value that fits in an {@code int} without overflow — used for duration validation. */
  private static final long MAX_INT_MS = Integer.MAX_VALUE;

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

    // container.getHost() returns the address from the test JVM perspective — correct for
    // test-side client connections. Works on Docker Desktop (macOS/Windows), Linux, devcontainers.
    final String hostname = container.getHost();
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

    final LatencyToxic toxic = LatencyToxic.builder()
        .name("latency")
        .latencyMs(toIntMs(latency, "latency"))
        .build();

    orchestrator.addToxic(container, proxyName, toxic);
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

    final TimeoutToxic toxic = TimeoutToxic.builder()
        .name("timeout")
        .timeoutMs(toIntMs(timeout, "timeout"))
        .toxicity(probability)
        .build();

    orchestrator.addToxic(container, proxyName, toxic);
  }

  @Override
  public void limitBandwidth(
      final GenericContainer<?> container, final String proxyName, final long rateKBps) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    if (rateKBps <= 0) {
      throw new IllegalArgumentException("rateKBps must be positive");
    }
    if (rateKBps > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          String.format("rateKBps exceeds maximum supported value (%d), got: %d",
              Integer.MAX_VALUE, rateKBps));
    }

    final BandwidthToxic toxic = BandwidthToxic.builder()
        .name("bandwidth")
        .rateKbps((int) rateKBps)
        .build();

    orchestrator.addToxic(container, proxyName, toxic);
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

    final SlowCloseToxic toxic = SlowCloseToxic.builder()
        .name("slow_close")
        .delayMs(toIntMs(delay, "delay"))
        .build();

    orchestrator.addToxic(container, proxyName, toxic);
  }

  @Override
  public void removeToxic(
      final GenericContainer<?> container, final String proxyName, final String toxicName) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");

    orchestrator.removeToxic(container, proxyName, toxicName);
  }

  @Override
  public void removeAllToxics(final GenericContainer<?> container, final String proxyName) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    orchestrator.removeAllToxics(container, proxyName);
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    orchestrator.reset(container);
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  // ==================== Private Helpers ====================

  /**
   * Convert a {@link Duration} to milliseconds as {@code int}, with overflow guard.
   *
   * <p>Toxiproxy toxic configs use {@code int} milliseconds. {@link Duration#toMillis()} returns
   * {@code long}. Values exceeding {@link Integer#MAX_VALUE} (~24.8 days) are rejected to prevent
   * silent truncation.
   *
   * @param duration duration to convert
   * @param paramName parameter name for error messages
   * @return milliseconds as {@code int}
   * @throws IllegalArgumentException if the duration exceeds {@link Integer#MAX_VALUE} milliseconds
   */
  private static int toIntMs(final Duration duration, final String paramName) {
    final long ms = duration.toMillis();
    if (ms > MAX_INT_MS) {
      throw new IllegalArgumentException(
          String.format("%s exceeds maximum supported value (%dms), got: %dms",
              paramName, MAX_INT_MS, ms));
    }
    return (int) ms;
  }
}
