/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.config;

/**
 * Configuration for Toxiproxy operations.
 *
 * <p>Centralizes all Toxiproxy-related configuration values (API URL, timeouts, etc.).
 *
 * <p>Use the builder for custom configurations:
 *
 * <pre>{@code
 * ToxiproxyConfig config = ToxiproxyConfig.builder()
 *     .apiUrl("http://localhost:8474")
 *     .proxyReadyTimeoutMs(5000)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ToxiproxyConfig {

  private final String apiUrl;
  private final int proxyReadyTimeoutMs;

  private ToxiproxyConfig(final Builder builder) {
    this.apiUrl = builder.apiUrl;
    this.proxyReadyTimeoutMs = builder.proxyReadyTimeoutMs;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static ToxiproxyConfig defaults() {
    return builder().build();
  }

  public String apiUrl() {
    return apiUrl;
  }

  public int proxyReadyTimeoutMs() {
    return proxyReadyTimeoutMs;
  }

  public static final class Builder {
    private String apiUrl = "http://localhost:8474";
    private int proxyReadyTimeoutMs = 2000;

    public Builder apiUrl(final String apiUrl) {
      this.apiUrl = apiUrl;
      return this;
    }

    public Builder proxyReadyTimeoutMs(final int proxyReadyTimeoutMs) {
      this.proxyReadyTimeoutMs = proxyReadyTimeoutMs;
      return this;
    }

    public ToxiproxyConfig build() {
      return new ToxiproxyConfig(this);
    }
  }
}
