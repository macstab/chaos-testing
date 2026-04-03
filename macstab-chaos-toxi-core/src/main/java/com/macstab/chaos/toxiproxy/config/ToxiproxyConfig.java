/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.config;

import java.util.Objects;

import lombok.NonNull;

/**
 * Configuration for Toxiproxy operations.
 *
 * <p>Centralizes all Toxiproxy-related configuration values (API URL, timeouts, polling intervals,
 * etc.). Enables environment-specific tuning and multi-instance testing.
 *
 * <h2>Default Configuration</h2>
 *
 * <pre>
 * API URL:                http://localhost:8474
 * Startup timeout:        10000 ms (10 seconds)
 * Poll interval:          100 ms
 * Proxy ready timeout:    2000 ms (2 seconds)
 * Connection timeout:     5000 ms (5 seconds)
 * Read timeout:           5000 ms (5 seconds)
 * </pre>
 *
 * <h2>Usage: Default Configuration</h2>
 *
 * <pre>{@code
 * // Use defaults (recommended for most cases)
 * ToxiproxyConfig config = ToxiproxyConfig.defaults();
 * ProxyChaosProvider provider = new ProxyChaosProvider(config);
 * }</pre>
 *
 * <h2>Usage: Custom Configuration</h2>
 *
 * <pre>{@code
 * // Custom configuration for CI/CD (longer timeouts)
 * ToxiproxyConfig config = ToxiproxyConfig.builder()
 *     .apiUrl("http://localhost:8474")
 *     .startupTimeoutMs(30000)        // 30s for slow CI
 *     .pollIntervalMs(200)            // Poll less frequently
 *     .proxyReadyTimeoutMs(5000)      // 5s for proxy readiness
 *     .build();
 *
 * ProxyChaosProvider provider = new ProxyChaosProvider(config);
 * }</pre>
 *
 * <h2>Usage: Multi-Instance Testing</h2>
 *
 * <pre>{@code
 * // Run multiple Toxiproxy instances on different ports
 * ToxiproxyConfig config1 = ToxiproxyConfig.builder()
 *     .apiUrl("http://localhost:8474")
 *     .build();
 *
 * ToxiproxyConfig config2 = ToxiproxyConfig.builder()
 *     .apiUrl("http://localhost:8475")
 *     .build();
 *
 * ProxyChaosProvider provider1 = new ProxyChaosProvider(config1);
 * ProxyChaosProvider provider2 = new ProxyChaosProvider(config2);
 * }</pre>
 *
 * <h2>Configuration Fields</h2>
 *
 * <ul>
 *   <li><strong>apiUrl:</strong> Toxiproxy API endpoint (default: http://localhost:8474)
 *   <li><strong>startupTimeoutMs:</strong> Max time to wait for Toxiproxy to start (default: 10000
 *       ms)
 *   <li><strong>pollIntervalMs:</strong> Health check polling interval (default: 100 ms)
 *   <li><strong>proxyReadyTimeoutMs:</strong> Max time to wait for proxy port to open (default:
 *       2000 ms)
 *   <li><strong>connectionTimeoutMs:</strong> HTTP connection timeout for API calls (default: 5000
 *       ms)
 *   <li><strong>readTimeoutMs:</strong> HTTP read timeout for API calls (default: 5000 ms)
 * </ul>
 *
 * <h2>Environment-Specific Tuning</h2>
 *
 * <ul>
 *   <li><strong>Local development:</strong> Use defaults
 *   <li><strong>CI/CD:</strong> Increase timeouts (30s startup, 5s proxy ready)
 *   <li><strong>Flaky networks:</strong> Increase connection/read timeouts (10s)
 *   <li><strong>Fast networks:</strong> Decrease poll interval (50ms)
 * </ul>
 *
 * <h2>Validation</h2>
 *
 * <p>All timeout values must be positive. API URL must not be null.
 *
 * <h2>Why Separate Config Object</h2>
 *
 * <p>Timeout and polling values are not constants — they must vary between local development (fast
 * Docker, small timeouts) and CI/CD (slower Docker daemon, slower network, larger timeouts).
 * Hardcoding them would require code changes for environment adaptation. A separate config object
 * allows callers to construct environment-appropriate configurations and inject them into
 * orchestrators and managers without modifying any other code.
 *
 * <h2>Immutability and Thread Safety</h2>
 *
 * <p>All fields are {@code private final}. The builder is a separate mutable object; once {@link
 * Builder#build()} is called, the resulting {@code ToxiproxyConfig} is unconditionally immutable
 * and thread-safe. Instances may be shared freely across threads and across modules (proxy module,
 * connection module, cache module) without synchronization.
 *
 * <h2>connectionTimeoutMs and readTimeoutMs: Currently Advisory</h2>
 *
 * <p>The HTTP calls in {@link com.macstab.chaos.toxiproxy.api.ToxiproxyApiClientImpl} are executed
 * via shell ({@code curl}) inside the container. The {@code connectionTimeoutMs} and {@code
 * readTimeoutMs} fields are present for forward compatibility but are not currently forwarded to
 * the {@code curl} command as {@code --connect-timeout} flags. This is a known limitation.
 * Contributors adding direct curl timeout flags should map these values accordingly.
 *
 * @see com.macstab.chaos.proxy.ProxyChaosProvider
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ToxiproxyConfig {

  private final String apiUrl;
  private final int startupTimeoutMs;
  private final int pollIntervalMs;
  private final int proxyReadyTimeoutMs;
  private final int connectionTimeoutMs;
  private final int readTimeoutMs;

  private ToxiproxyConfig(final Builder builder) {
    this.apiUrl = Objects.requireNonNull(builder.apiUrl, "apiUrl must not be null");
    this.startupTimeoutMs = builder.startupTimeoutMs;
    this.pollIntervalMs = builder.pollIntervalMs;
    this.proxyReadyTimeoutMs = builder.proxyReadyTimeoutMs;
    this.connectionTimeoutMs = builder.connectionTimeoutMs;
    this.readTimeoutMs = builder.readTimeoutMs;

    validate();
  }

  /**
   * Create a builder for custom configuration.
   *
   * @return new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Create configuration with default values.
   *
   * <p>Recommended for most use cases.
   *
   * @return default configuration
   */
  public static ToxiproxyConfig defaults() {
    return builder().build();
  }

  /**
   * Get Toxiproxy API endpoint URL.
   *
   * @return API URL (e.g., "http://localhost:8474")
   */
  public String apiUrl() {
    return apiUrl;
  }

  /**
   * Get maximum startup timeout in milliseconds.
   *
   * <p>Maximum time to wait for Toxiproxy server to start and become healthy.
   *
   * @return startup timeout in milliseconds
   */
  public int startupTimeoutMs() {
    return startupTimeoutMs;
  }

  /**
   * Get health check polling interval in milliseconds.
   *
   * <p>How often to check if Toxiproxy API is responding during startup.
   *
   * @return poll interval in milliseconds
   */
  public int pollIntervalMs() {
    return pollIntervalMs;
  }

  /**
   * Get proxy readiness timeout in milliseconds.
   *
   * <p>Maximum time to wait for a proxy port to start listening after creation.
   *
   * @return proxy ready timeout in milliseconds
   */
  public int proxyReadyTimeoutMs() {
    return proxyReadyTimeoutMs;
  }

  /**
   * Get HTTP connection timeout in milliseconds.
   *
   * <p>Connection timeout for Toxiproxy API HTTP calls.
   *
   * @return connection timeout in milliseconds
   */
  public int connectionTimeoutMs() {
    return connectionTimeoutMs;
  }

  /**
   * Get HTTP read timeout in milliseconds.
   *
   * <p>Read timeout for Toxiproxy API HTTP responses.
   *
   * @return read timeout in milliseconds
   */
  public int readTimeoutMs() {
    return readTimeoutMs;
  }

  /** Validate all configuration values. */
  private void validate() {
    if (startupTimeoutMs <= 0) {
      throw new IllegalArgumentException(
          "startupTimeoutMs must be positive, got: " + startupTimeoutMs);
    }
    if (pollIntervalMs <= 0) {
      throw new IllegalArgumentException("pollIntervalMs must be positive, got: " + pollIntervalMs);
    }
    if (proxyReadyTimeoutMs <= 0) {
      throw new IllegalArgumentException(
          "proxyReadyTimeoutMs must be positive, got: " + proxyReadyTimeoutMs);
    }
    if (connectionTimeoutMs <= 0) {
      throw new IllegalArgumentException(
          "connectionTimeoutMs must be positive, got: " + connectionTimeoutMs);
    }
    if (readTimeoutMs <= 0) {
      throw new IllegalArgumentException("readTimeoutMs must be positive, got: " + readTimeoutMs);
    }
  }

  /** Builder for ToxiproxyConfig. */
  public static final class Builder {
    private String apiUrl = "http://localhost:8474";
    private int startupTimeoutMs = 10000;
    private int pollIntervalMs = 100;
    private int proxyReadyTimeoutMs = 2000;
    private int connectionTimeoutMs = 5000;
    private int readTimeoutMs = 5000;

    /**
     * Set Toxiproxy API endpoint URL.
     *
     * @param apiUrl API URL (e.g., "http://localhost:8474")
     * @return this builder
     */
    public Builder apiUrl(@NonNull final String apiUrl) {
      this.apiUrl = apiUrl;
      return this;
    }

    /**
     * Set maximum startup timeout.
     *
     * @param startupTimeoutMs timeout in milliseconds (must be positive)
     * @return this builder
     */
    public Builder startupTimeoutMs(final int startupTimeoutMs) {
      this.startupTimeoutMs = startupTimeoutMs;
      return this;
    }

    /**
     * Set health check polling interval.
     *
     * @param pollIntervalMs interval in milliseconds (must be positive)
     * @return this builder
     */
    public Builder pollIntervalMs(final int pollIntervalMs) {
      this.pollIntervalMs = pollIntervalMs;
      return this;
    }

    /**
     * Set proxy readiness timeout.
     *
     * @param proxyReadyTimeoutMs timeout in milliseconds (must be positive)
     * @return this builder
     */
    public Builder proxyReadyTimeoutMs(final int proxyReadyTimeoutMs) {
      this.proxyReadyTimeoutMs = proxyReadyTimeoutMs;
      return this;
    }

    /**
     * Set HTTP connection timeout.
     *
     * @param connectionTimeoutMs timeout in milliseconds (must be positive)
     * @return this builder
     */
    public Builder connectionTimeoutMs(final int connectionTimeoutMs) {
      this.connectionTimeoutMs = connectionTimeoutMs;
      return this;
    }

    /**
     * Set HTTP read timeout.
     *
     * @param readTimeoutMs timeout in milliseconds (must be positive)
     * @return this builder
     */
    public Builder readTimeoutMs(final int readTimeoutMs) {
      this.readTimeoutMs = readTimeoutMs;
      return this;
    }

    /**
     * Build configuration instance.
     *
     * @return immutable configuration
     * @throws IllegalArgumentException if any timeout is non-positive
     */
    public ToxiproxyConfig build() {
      return new ToxiproxyConfig(this);
    }
  }
}
