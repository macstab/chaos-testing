/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.redis.config;

import java.util.Objects;

/**
 * Configuration for Redis cache chaos injection.
 *
 * <p>Centralizes Redis port assignments and proxy naming so callers never hardcode magic numbers.
 * All values have sensible defaults; override only when a non-standard Redis setup is used.
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <h2>Default Configuration</h2>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>
 * Redis port:   6379   (standard Redis listen port)
 * Proxy port:   16379  (Toxiproxy intercept port, convention: servicePort + 10000)
 * Proxy name:   "redis_cache"
 * </pre>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <h2>Usage: Default Configuration</h2>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>{@code
 * // Recommended — works for standard Redis setup
 * RedisChaosConfig config = RedisChaosConfig.defaults();
 * CacheChaos chaos = new RedisCacheChaosProvider(config);
 * }</pre>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <h2>Usage: Custom Ports</h2>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>{@code
 * // Non-standard Redis port (e.g., Redis Cluster member, Sentinel setup)
 * RedisChaosConfig config = RedisChaosConfig.builder()
 *     .redisPort(6380)
 *     .proxyPort(16380)
 *     .proxyName("redis_primary")
 *     .build();
 *
 * CacheChaos chaos = new RedisCacheChaosProvider(config);
 * }</pre>
 *
 * @see com.macstab.chaos.cache.redis.RedisCacheChaosProvider
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisChaosConfig {

  private static final int DEFAULT_REDIS_PORT = 6379;
  private static final int DEFAULT_PROXY_PORT = 16379;
  private static final String DEFAULT_PROXY_NAME = "redis_cache";

  private final int redisPort;
  private final int proxyPort;
  private final String proxyName;

  private RedisChaosConfig(final Builder builder) {
    this.redisPort = builder.redisPort;
    this.proxyPort = builder.proxyPort;
    this.proxyName = Objects.requireNonNull(builder.proxyName, "proxyName must not be null");
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
   * <p>Recommended for standard Redis containers on port 6379.
   *
   * @return default configuration
   */
  public static RedisChaosConfig defaults() {
    return builder().build();
  }

  /**
   * Redis listen port inside the container.
   *
   * @return Redis port (default: 6379)
   */
  public int redisPort() {
    return redisPort;
  }

  /**
   * Toxiproxy proxy port inside the container.
   *
   * <p>Convention: {@code servicePort + 10000}. Must not equal {@link #redisPort()}.
   *
   * @return proxy port (default: 16379)
   */
  public int proxyPort() {
    return proxyPort;
  }

  /**
   * Toxiproxy proxy name used for toxic management.
   *
   * <p>Must be unique per container when multiple modules share the same container.
   *
   * @return proxy name (default: "redis_cache")
   */
  public String proxyName() {
    return proxyName;
  }

  /** Validate port ranges and uniqueness. */
  private void validate() {
    validatePort(redisPort, "redisPort");
    validatePort(proxyPort, "proxyPort");
    if (redisPort == proxyPort) {
      throw new IllegalArgumentException(
          String.format("redisPort and proxyPort must differ, both are: %d", redisPort));
    }
    if (proxyName.isBlank()) {
      throw new IllegalArgumentException("proxyName must not be blank");
    }
  }

  private static void validatePort(final int port, final String name) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(
          String.format("%s must be in range [1, 65535], got: %d", name, port));
    }
  }

  /** Builder for {@link RedisChaosConfig}. */
  public static final class Builder {

    private int redisPort = DEFAULT_REDIS_PORT;
    private int proxyPort = DEFAULT_PROXY_PORT;
    private String proxyName = DEFAULT_PROXY_NAME;

    /**
     * Set Redis listen port.
     *
     * @param redisPort port in range [1, 65535]
     * @return this builder
     */
    public Builder redisPort(final int redisPort) {
      this.redisPort = redisPort;
      return this;
    }

    /**
     * Set Toxiproxy intercept port.
     *
     * @param proxyPort port in range [1, 65535], must differ from redisPort
     * @return this builder
     */
    public Builder proxyPort(final int proxyPort) {
      this.proxyPort = proxyPort;
      return this;
    }

    /**
     * Set Toxiproxy proxy name.
     *
     * @param proxyName non-blank proxy identifier
     * @return this builder
     */
    public Builder proxyName(final String proxyName) {
      this.proxyName = proxyName;
      return this;
    }

    /**
     * Build configuration instance.
     *
     * @return immutable configuration
     * @throws IllegalArgumentException if ports are invalid or equal
     */
    public RedisChaosConfig build() {
      return new RedisChaosConfig(this);
    }
  }
}
