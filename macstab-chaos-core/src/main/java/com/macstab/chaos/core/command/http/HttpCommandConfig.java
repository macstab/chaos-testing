/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.http;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import lombok.Builder;
import lombok.Value;

/**
 * Configuration for HTTP command building.
 *
 * <p>Immutable value object with sensible defaults. Use builder for customization.
 *
 * <p><strong>Example (minimal - use defaults):</strong>
 *
 * <pre>
 * HttpCommandBuilder builder = new CurlCommandBuilder();
 * </pre>
 *
 * <p><strong>Example (with configuration):</strong>
 *
 * <pre>
 * HttpCommandConfig config = HttpCommandConfig.builder()
 *   .connectionTimeout(Duration.ofSeconds(5))
 *   .failOnHttpError(true)
 *   .build();
 * HttpCommandBuilder builder = new CurlCommandBuilder(config);
 * </pre>
 *
 * <p><strong>Example (with proxy):</strong>
 *
 * <pre>
 * ProxyConfig proxy = ProxyConfig.builder()
 *   .host("proxy.corp.com")
 *   .port(8080)
 *   .username("user")
 *   .password("pass")
 *   .build();
 *
 * HttpCommandConfig config = HttpCommandConfig.builder()
 *   .proxy(Optional.of(proxy))
 *   .build();
 * </pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Value
@Builder
public class HttpCommandConfig {

  /**
   * Connection timeout (default: 30 seconds).
   *
   * <p>Maximum time to wait for connection establishment.
   */
  @Builder.Default Duration connectionTimeout = Duration.ofSeconds(30);

  /**
   * Maximum retry attempts on transient failures (default: 0 = no retries).
   *
   * <p>Only retries on network errors, not HTTP errors.
   */
  @Builder.Default int maxRetries = 0;

  /**
   * Follow HTTP redirects (default: true).
   *
   * <p>Automatically follow 3xx redirect responses.
   */
  @Builder.Default boolean followRedirects = true;

  /**
   * Fail on HTTP 4xx/5xx errors (default: false).
   *
   * <p>When true, non-2xx/3xx responses result in non-zero exit code.
   */
  @Builder.Default boolean failOnHttpError = false;

  /**
   * Silent mode - suppress progress output (default: true).
   *
   * <p>Reduces stderr noise from HTTP tools.
   */
  @Builder.Default boolean silent = true;

  /**
   * HTTP proxy configuration (optional).
   *
   * <p>When present, routes all HTTP requests through specified proxy.
   */
  @Builder.Default Optional<ProxyConfig> proxy = Optional.empty();

  /**
   * Custom CA certificate path (optional).
   *
   * <p>For environments with self-signed certificates or custom CA.
   */
  @Builder.Default Optional<Path> caCertificatePath = Optional.empty();

  /**
   * Custom HTTP headers (optional).
   *
   * <p>Additional headers to include in requests (e.g., authentication tokens).
   */
  @Builder.Default Map<String, String> customHeaders = Map.of();

  /**
   * HTTP proxy configuration.
   *
   * <p>Supports optional authentication (username/password).
   *
   * @author Christian Schnapka - Macstab GmbH
   */
  @Value
  @Builder
  public static class ProxyConfig {
    /** Proxy hostname or IP address. */
    String host;

    /** Proxy port number. */
    int port;

    /** Proxy authentication username (optional). */
    @Builder.Default Optional<String> username = Optional.empty();

    /** Proxy authentication password (optional). */
    @Builder.Default Optional<String> password = Optional.empty();
  }
}
