/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.http;

import java.util.Objects;

/**
 * HTTP command builder using curl (Linux/macOS).
 *
 * <p>Generates curl commands for HTTP operations. Supports configuration via {@link
 * HttpCommandConfig}.
 *
 * <p><strong>Platform Support:</strong>
 *
 * <ul>
 *   <li>Linux (Debian, Ubuntu, RHEL, Alpine with curl installed)
 *   <li>macOS (curl pre-installed)
 *   <li>BSD (FreeBSD/OpenBSD with curl package)
 * </ul>
 *
 * <p><strong>Security:</strong> Properly escapes shell metacharacters using POSIX single-quote
 * method.
 *
 * <p><strong>Example (minimal):</strong>
 *
 * <pre>{@code
 * HttpCommandBuilder builder = new CurlCommandBuilder();
 * String cmd = builder.buildGetRequest("http://localhost:8080/api");
 * // curl -s 'http://localhost:8080/api' 2>&1
 * }</pre>
 *
 * <p><strong>Example (with configuration):</strong>
 *
 * <pre>{@code
 * HttpCommandConfig config = HttpCommandConfig.builder()
 *   .connectionTimeout(Duration.ofSeconds(5))
 *   .failOnHttpError(true)
 *   .maxRetries(3)
 *   .build();
 * HttpCommandBuilder builder = new CurlCommandBuilder(config);
 * String cmd = builder.buildGetRequest("http://localhost/api");
 * // curl -s -f --max-time 5 --retry 3 'http://localhost/api' 2>&1
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class CurlCommandBuilder implements HttpCommandBuilder {

  private final HttpCommandConfig config;

  /** Create curl builder with default configuration. */
  public CurlCommandBuilder() {
    this.config = HttpCommandConfig.builder().build();
  }

  /**
   * Create curl builder with custom configuration.
   *
   * @param config HTTP command configuration (must not be null)
   * @throws NullPointerException if config is null
   */
  public CurlCommandBuilder(final HttpCommandConfig config) {
    this.config = Objects.requireNonNull(config, "config must not be null");
  }

  @Override
  public String buildGetRequest(final String url) {
    Objects.requireNonNull(url, "url must not be null");
    return buildCommand("GET", url, null);
  }

  @Override
  public String buildGetRequestFailOnError(final String url) {
    Objects.requireNonNull(url, "url must not be null");
    return buildCommand("GET", url, null, true);
  }

  @Override
  public String buildPostJsonRequest(final String url, final String jsonPayload) {
    Objects.requireNonNull(url, "url must not be null");
    Objects.requireNonNull(jsonPayload, "jsonPayload must not be null");
    return buildCommand("POST", url, jsonPayload);
  }

  @Override
  public String buildPutJsonRequest(final String url, final String jsonPayload) {
    Objects.requireNonNull(url, "url must not be null");
    Objects.requireNonNull(jsonPayload, "jsonPayload must not be null");
    return buildCommand("PUT", url, jsonPayload);
  }

  @Override
  public String buildDeleteRequest(final String url) {
    Objects.requireNonNull(url, "url must not be null");
    return buildCommand("DELETE", url, null);
  }

  @Override
  public String buildDownloadRequest(final String url, final String outputPath) {
    Objects.requireNonNull(url, "url must not be null");
    Objects.requireNonNull(outputPath, "outputPath must not be null");

    final StringBuilder cmd = new StringBuilder("curl");

    if (config.isSilent()) {
      cmd.append(" -s");
    }

    // Always follow redirects for downloads (GitHub releases redirect)
    cmd.append(" -L");

    final long timeoutSeconds = config.getConnectionTimeout().toSeconds();
    if (timeoutSeconds > 0) {
      cmd.append(" --max-time ").append(timeoutSeconds);
    }

    cmd.append(" -o ").append(escapeShellArg(outputPath));
    cmd.append(" ").append(escapeShellArg(url));
    cmd.append(" 2>&1");

    return cmd.toString();
  }

  @Override
  public boolean isAvailable() {
    try {
      // Try: which curl (POSIX systems)
      final ProcessBuilder pb1 = new ProcessBuilder("which", "curl");
      pb1.redirectErrorStream(true);
      final Process p1 = pb1.start();
      if (p1.waitFor() == 0) {
        return true;
      }

      // Fallback: command -v curl (works in sh/bash)
      final ProcessBuilder pb2 = new ProcessBuilder("sh", "-c", "command -v curl");
      pb2.redirectErrorStream(true);
      final Process p2 = pb2.start();
      return p2.waitFor() == 0;

    } catch (final Exception e) {
      // If check fails, assume not available
      return false;
    }
  }

  @Override
  public String getToolName() {
    return "curl";
  }

  // ==================== Private Implementation ====================

  /**
   * Build curl command with optional fail-on-error flag.
   *
   * @param method HTTP method
   * @param url target URL
   * @param payload optional request body (for POST/PUT)
   * @param forceFailOnError override config fail-on-error (for buildGetRequestFailOnError)
   * @return curl command string
   */
  private String buildCommand(
      final String method, final String url, final String payload, final boolean forceFailOnError) {
    final StringBuilder cmd = new StringBuilder("curl");

    // Silent mode (suppress progress bar)
    if (config.isSilent()) {
      cmd.append(" -s");
    }

    // Fail on HTTP errors (4xx/5xx)
    if (forceFailOnError || config.isFailOnHttpError()) {
      cmd.append(" -f");
    }

    // Connection timeout
    final long timeoutSeconds = config.getConnectionTimeout().toSeconds();
    if (timeoutSeconds > 0) {
      cmd.append(" --max-time ").append(timeoutSeconds);
    }

    // Retry on transient failures
    if (config.getMaxRetries() > 0) {
      cmd.append(" --retry ").append(config.getMaxRetries());
    }

    // Follow redirects
    if (config.isFollowRedirects()) {
      cmd.append(" -L");
    }

    // HTTP proxy
    config
        .getProxy()
        .ifPresent(
            proxy -> {
              cmd.append(" -x ").append(escapeShellArg(proxy.getHost() + ":" + proxy.getPort()));

              // Proxy authentication
              if (proxy.getUsername().isPresent() && proxy.getPassword().isPresent()) {
                final String auth = proxy.getUsername().get() + ":" + proxy.getPassword().get();
                cmd.append(" --proxy-user ").append(escapeShellArg(auth));
              }
            });

    // Custom CA certificate
    config
        .getCaCertificatePath()
        .ifPresent(
            path -> {
              cmd.append(" --cacert ").append(escapeShellArg(path.toString()));
            });

    // Custom headers
    config
        .getCustomHeaders()
        .forEach(
            (name, value) -> {
              cmd.append(" -H ").append(escapeShellArg(name + ": " + value));
            });

    // HTTP method (explicit for non-GET)
    if (!"GET".equals(method)) {
      cmd.append(" -X ").append(method);
    }

    // Content-Type for JSON payloads
    if (payload != null) {
      cmd.append(" -H ").append(escapeShellArg("Content-Type: application/json"));
      cmd.append(" -d ").append(escapeShellArg(payload));
    }

    // URL (always escaped)
    cmd.append(" ").append(escapeShellArg(url));

    // Redirect stderr to stdout (capture all output)
    cmd.append(" 2>&1");

    return cmd.toString();
  }

  /**
   * Build curl command (delegates to 4-arg version).
   *
   * @param method HTTP method
   * @param url target URL
   * @param payload optional request body
   * @return curl command string
   */
  private String buildCommand(final String method, final String url, final String payload) {
    return buildCommand(method, url, payload, false);
  }

  /**
   * Escape argument for POSIX shell using single-quote method.
   *
   * <p>Strategy: Wrap everything in single quotes, replace literal single quotes with '\'' (end
   * quote, escaped single quote, start quote).
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>{@code hello} → {@code 'hello'}
   *   <li>{@code O'Reilly} → {@code 'O'\''Reilly'}
   *   <li>{@code $HOME} → {@code '$HOME'} (no expansion)
   * </ul>
   *
   * @param input raw string
   * @return shell-escaped string
   */
  private String escapeShellArg(final String input) {
    // Replace ' with '\'' (end quote, escaped quote, start quote)
    final String escaped = input.replace("'", "'\\''");
    return "'" + escaped + "'";
  }
}
