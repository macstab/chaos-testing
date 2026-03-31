/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy;

import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ProxyChaos;
import com.macstab.chaos.toxiproxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator;
import com.macstab.chaos.toxiproxy.config.ProxyConfiguration;
import com.macstab.chaos.toxiproxy.toxic.BandwidthToxic;
import com.macstab.chaos.toxiproxy.toxic.LatencyToxic;
import com.macstab.chaos.toxiproxy.toxic.LimitDataToxic;
import com.macstab.chaos.toxiproxy.toxic.SlowCloseToxic;
import com.macstab.chaos.toxiproxy.toxic.TimeoutToxic;

/**
 * Provider for universal TCP proxy-based chaos injection.
 *
 * <p>Enables transparent fault injection into any TCP service without modifying application code.
 * Clients connect via the address returned by {@link #createProxy} — traffic routes through
 * Toxiproxy, which applies the configured faults.
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <h2>⚠️ CRITICAL: deleteProxy vs reset — You Must Read This</h2>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p><strong>One Toxiproxy process handles ALL proxies inside a container.</strong> When multiple
 * modules create proxies on the same container (e.g., cache chaos creates {@code "redis"}, database
 * chaos creates {@code "postgres"}), they share the same Toxiproxy process. Cleanup must be done
 * carefully:
 *
 * <ul>
 *   <li>⛔ {@link #reset} is <strong>nuclear</strong>: kills Toxiproxy and removes
 *       <strong>all</strong> iptables rules. Every proxy from every module is gone. Only use in
 *       {@code @AfterAll}.
 *   <li>✅ {@link #deleteProxy} is <strong>surgical</strong>: removes one named proxy and its
 *       iptables rule. Everything else stays intact. Use in {@code @AfterEach}.
 * </ul>
 *
 * <pre>{@code
 * // ✅ CORRECT — remove only your proxy after each test
 * @AfterEach
 * void cleanup() {
 *     chaos.deleteProxy(container, "redis");   // only "redis" removed
 * }
 *
 * // ✅ CORRECT — nuclear cleanup when container is done
 * @AfterAll
 * static void teardown() {
 *     chaos.reset(container);                  // kills everything — intentional
 * }
 *
 * // ❌ WRONG — wipes ALL proxies, breaking other modules
 * @AfterEach
 * void cleanup() {
 *     chaos.reset(container);   // kills "postgres" proxy too. Don't.
 * }
 * }</pre>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <h2>Quick Start</h2>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>{@code
 * ProxyChaos chaos = new ProxyChaosProvider();
 *
 * String hostname = chaos.createProxy(container, "redis", 6379, 16379);
 * chaos.addLatency(container, "redis", Duration.ofMillis(200));
 *
 * Jedis client = new Jedis(hostname, 6379);  // Connect via hostname, NOT localhost
 *
 * chaos.removeToxic(container, "redis", "latency");  // Remove just latency
 * chaos.deleteProxy(container, "redis");             // Remove proxy cleanly
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

    final LatencyToxic toxic =
        LatencyToxic.builder().name("latency").latencyMs(toIntMs(latency, "latency")).build();

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

    final TimeoutToxic toxic =
        TimeoutToxic.builder()
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
          String.format(
              "rateKBps exceeds maximum supported value (%d), got: %d",
              Integer.MAX_VALUE, rateKBps));
    }

    final BandwidthToxic toxic =
        BandwidthToxic.builder().name("bandwidth").rateKbps((int) rateKBps).build();

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

    final SlowCloseToxic toxic =
        SlowCloseToxic.builder().name("slow_close").delayMs(toIntMs(delay, "delay")).build();

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

  /**
   * ✅ Delete one proxy and its iptables rule — safe for {@code @AfterEach}.
   *
   * <p>Removes only the named proxy. The Toxiproxy process and all other proxies stay alive. Use
   * this for per-test cleanup when other modules may be sharing the same container.
   *
   * @param container target container
   * @param proxyName name of the proxy to delete
   * @see #reset(GenericContainer) for nuclear full cleanup
   */
  @Override
  public void deleteProxy(final GenericContainer<?> container, final String proxyName) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    orchestrator.deleteProxy(container, proxyName);
  }

  @Override
  public void addLimitData(
      final GenericContainer<?> container, final String proxyName, final long bytes) {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must be >= 0, got: " + bytes);
    }

    final LimitDataToxic toxic = LimitDataToxic.builder().name("limit_data").bytes(bytes).build();

    orchestrator.addToxic(container, proxyName, toxic);
  }

  /**
   * ⛔ Nuclear reset — kills Toxiproxy and removes ALL iptables rules.
   *
   * <p>This destroys <strong>every proxy in the container</strong> — not just the ones you created.
   * Only use in {@code @AfterAll} when the container itself is being discarded. For per-test
   * cleanup, use {@link #deleteProxy} instead.
   *
   * @param container target container (no-op if not running)
   * @see #deleteProxy(GenericContainer, String) for surgical single-proxy removal
   */
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
          String.format(
              "%s exceeds maximum supported value (%dms), got: %dms", paramName, MAX_INT_MS, ms));
    }
    return (int) ms;
  }
}
