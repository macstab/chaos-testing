/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.api;

import java.io.IOException;
import java.util.Objects;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.command.http.HttpCommandBuilder;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of Toxiproxy API client using platform-agnostic HTTP commands.
 *
 * <p>Caches platform detection per container for performance. Platform is detected once per
 * container and reused for all subsequent operations.
 *
 * <p><strong>Thread-safety:</strong> Platform is immutable, safe to cache. Container reference is
 * checked on each call to invalidate cache when container changes.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyApiClientImpl implements ToxiproxyApiClient {

  private final String apiUrl;

  // Platform caching (lazy initialization)
  private Platform cachedPlatform;
  private GenericContainer<?> cachedContainer;

  /**
   * Create API client with default URL.
   *
   * @param apiUrl Toxiproxy API base URL (e.g., "http://localhost:8474")
   */
  public ToxiproxyApiClientImpl(final String apiUrl) {
    this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl must not be null");
  }

  /**
   * Create API client with default localhost URL.
   */
  public ToxiproxyApiClientImpl() {
    this("http://localhost:8474");
  }

  @Override
  public boolean isApiReady(final GenericContainer<?> container, final Shell shell) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(shell, "shell must not be null");

    try {
      final Platform platform = getPlatform(container);
      final HttpCommandBuilder http = platform.getHttpCommandBuilder();
      final String url = apiUrl + "/proxies";
      final String cmd = http.buildGetRequestFailOnError(url);
      final ExecResult result = shell.exec(container, cmd);
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      log.debug("API not ready: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public boolean proxyExists(
      final GenericContainer<?> container, final Shell shell, final String proxyName)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(shell, "shell must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    try {
      final Platform platform = getPlatform(container);
      final HttpCommandBuilder http = platform.getHttpCommandBuilder();
      final String url = String.format("%s/proxies/%s", apiUrl, proxyName);
      final String cmd = http.buildGetRequestFailOnError(url);
      final ExecResult result = shell.exec(container, cmd);
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      throw new IOException("Failed to check if proxy exists: " + proxyName, e);
    }
  }

  @Override
  public void createProxy(
      final GenericContainer<?> container, final Shell shell, final ProxyConfiguration config)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(shell, "shell must not be null");
    Objects.requireNonNull(config, "config must not be null");

    try {
      final Platform platform = getPlatform(container);
      final HttpCommandBuilder http = platform.getHttpCommandBuilder();
      final String url = apiUrl + "/proxies";
      final String json = buildProxyJson(config);
      final String command = http.buildPostJsonRequest(url, json);

      final ExecResult result = shell.exec(container, command);

      if (result.getExitCode() != 0) {
        throw new IOException(
            String.format(
                "Failed to create proxy '%s': %s", config.getProxyName(), result.getStderr()));
      }

      log.debug("Created proxy '{}' via API", config.getProxyName());

    } catch (final Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException("Failed to create proxy: " + config.getProxyName(), e);
    }
  }

  @Override
  public void deleteProxy(
      final GenericContainer<?> container, final Shell shell, final String proxyName)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(shell, "shell must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    try {
      final Platform platform = getPlatform(container);
      final HttpCommandBuilder http = platform.getHttpCommandBuilder();
      final String url = String.format("%s/proxies/%s", apiUrl, proxyName);
      final String command = http.buildDeleteRequest(url);

      final ExecResult result = shell.exec(container, command);

      if (result.getExitCode() != 0) {
        throw new IOException(
            String.format("Failed to delete proxy '%s': %s", proxyName, result.getStderr()));
      }

      log.debug("Deleted proxy '{}' via API", proxyName);

    } catch (final Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException("Failed to delete proxy: " + proxyName, e);
    }
  }

  @Override
  public boolean toxicExists(
      final GenericContainer<?> container,
      final Shell shell,
      final String proxyName,
      final String toxicName)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(shell, "shell must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");

    try {
      final Platform platform = getPlatform(container);
      final HttpCommandBuilder http = platform.getHttpCommandBuilder();
      final String url = String.format("%s/proxies/%s/toxics", apiUrl, proxyName);
      final String cmd = http.buildGetRequest(url);
      
      // Fetch toxics list, then grep for toxic name
      final String grepCmd = String.format("%s | grep -q '\"name\":\"%s\"'", cmd, toxicName);

      final ExecResult result = shell.exec(container, grepCmd);
      return result.getExitCode() == 0;

    } catch (final Exception e) {
      throw new IOException(
          String.format("Failed to check if toxic exists: %s/%s", proxyName, toxicName), e);
    }
  }

  @Override
  public void addToxic(
      final GenericContainer<?> container,
      final Shell shell,
      final String proxyName,
      final String toxicName,
      final String toxicType,
      final String attributes,
      final double toxicity)
      throws IOException {

    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(shell, "shell must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");
    Objects.requireNonNull(toxicType, "toxicType must not be null");
    Objects.requireNonNull(attributes, "attributes must not be null");

    validateToxicity(toxicity);

    try {
      final Platform platform = getPlatform(container);
      final HttpCommandBuilder http = platform.getHttpCommandBuilder();
      final String url = String.format("%s/proxies/%s/toxics", apiUrl, proxyName);
      final String json = buildToxicJson(toxicName, toxicType, attributes, toxicity);
      final String command = http.buildPostJsonRequest(url, json);

      final ExecResult result = shell.exec(container, command);

      if (result.getExitCode() != 0) {
        throw new IOException(
            String.format("Failed to add toxic '%s': %s", toxicName, result.getStderr()));
      }

      log.debug("Added toxic '{}' to proxy '{}' via API", toxicName, proxyName);

    } catch (final Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException(
          String.format("Failed to add toxic: %s/%s", proxyName, toxicName), e);
    }
  }

  // ==================== Private Helper Methods ====================

  /**
   * Build JSON for proxy creation.
   *
   * @param config proxy configuration
   * @return JSON string
   */
  private String buildProxyJson(final ProxyConfiguration config) {
    return String.format(
        "{\"name\":\"%s\",\"listen\":\"0.0.0.0:%d\",\"upstream\":\"localhost:%d\",\"enabled\":true}",
        config.getProxyName(), config.getProxyPort(), config.getServicePort());
  }

  /**
   * Build JSON for toxic creation.
   *
   * @param toxicName toxic name
   * @param toxicType toxic type
   * @param attributes toxic attributes (already JSON)
   * @param toxicity probability
   * @return JSON string
   */
  private String buildToxicJson(
      final String toxicName,
      final String toxicType,
      final String attributes,
      final double toxicity) {

    return String.format(
        "{\"name\":\"%s\",\"type\":\"%s\",\"attributes\":%s,\"toxicity\":%.2f}",
        toxicName, toxicType, attributes, toxicity);
  }

  /**
   * Validate toxicity is in valid range.
   *
   * @param toxicity toxicity value
   * @throws IllegalArgumentException if invalid
   */
  private void validateToxicity(final double toxicity) {
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  /**
   * Get platform for container, using cache when available.
   *
   * <p>Platform is detected once per container and cached. Cache is invalidated when container
   * reference changes.
   *
   * @param container target container
   * @return platform instance (cached or newly detected)
   */
  private Platform getPlatform(final GenericContainer<?> container) {
    if (cachedPlatform == null || cachedContainer != container) {
      cachedPlatform = PlatformDetector.detect(container);
      cachedContainer = container;
      log.trace("Platform detected and cached for container: {}", container.getDockerImageName());
    }
    return cachedPlatform;
  }
}
