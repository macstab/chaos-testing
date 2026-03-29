/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.api;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.Container.ExecResult;

import com.macstab.chaos.core.command.http.HttpCommandBuilder;
import com.macstab.chaos.proxy.internal.ContainerContext;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of Toxiproxy API client.
 *
 * <p>All HTTP communication uses the platform-appropriate {@link HttpCommandBuilder} obtained from
 * {@link ContainerContext#http()}. No {@code execInContainer} calls, no hardcoded tool names.
 *
 * <p>Exception handling is centralized in {@link #executeApiCall} — eliminates repeated {@code
 * catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(...) }}
 * boilerplate throughout this class.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyApiClientImpl implements ToxiproxyApiClient {

  /** Pattern to extract toxic names from Toxiproxy JSON response array. */
  private static final Pattern TOXIC_NAME_PATTERN = Pattern.compile("\"name\":\\s*\"([^\"]+)\"");

  private final String apiUrl;

  /**
   * Create API client with specified Toxiproxy API URL.
   *
   * @param apiUrl Toxiproxy API base URL (e.g., "http://localhost:8474")
   * @throws NullPointerException if apiUrl is null
   */
  public ToxiproxyApiClientImpl(final String apiUrl) {
    this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl must not be null");
  }

  @Override
  public boolean isApiReady(final ContainerContext ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");

    try {
      final String cmd = ctx.http().buildGetRequestFailOnError(apiUrl + "/proxies");
      final ExecResult result = ctx.shell().exec(ctx.container(), cmd);
      return result.getExitCode() == 0;
    } catch (final Exception e) {
      log.debug("Toxiproxy API not ready: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public boolean proxyExists(final ContainerContext ctx, final String proxyName)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    return executeApiCall(
        "Failed to check if proxy exists: " + proxyName,
        () -> {
          final String url = String.format("%s/proxies/%s", apiUrl, proxyName);
          final ExecResult result =
              ctx.shell().exec(ctx.container(), ctx.http().buildGetRequestFailOnError(url));
          return result.getExitCode() == 0;
        });
  }

  @Override
  public void createProxy(final ContainerContext ctx, final ProxyConfiguration config)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(config, "config must not be null");

    executeApiCall(
        "Failed to create proxy: " + config.getProxyName(),
        () -> {
          final String command =
              ctx.http().buildPostJsonRequest(apiUrl + "/proxies", buildProxyJson(config));
          final ExecResult result = ctx.shell().exec(ctx.container(), command);

          if (result.getExitCode() != 0) {
            throw new IOException(
                String.format(
                    "Failed to create proxy '%s': %s", config.getProxyName(), result.getStderr()));
          }

          log.debug("Created proxy '{}' via API", config.getProxyName());
        });
  }

  @Override
  public void deleteProxy(final ContainerContext ctx, final String proxyName) throws IOException {
    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    executeApiCall(
        "Failed to delete proxy: " + proxyName,
        () -> {
          final String url = String.format("%s/proxies/%s", apiUrl, proxyName);
          final ExecResult result =
              ctx.shell().exec(ctx.container(), ctx.http().buildDeleteRequest(url));

          if (result.getExitCode() != 0) {
            throw new IOException(
                String.format("Failed to delete proxy '%s': %s", proxyName, result.getStderr()));
          }

          log.debug("Deleted proxy '{}' via API", proxyName);
        });
  }

  @Override
  public boolean toxicExists(
      final ContainerContext ctx, final String proxyName, final String toxicName)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");

    try {
      return listToxics(ctx, proxyName).contains(toxicName);
    } catch (final IOException e) {
      throw new IOException(
          String.format("Failed to check if toxic exists: %s/%s", proxyName, toxicName), e);
    }
  }

  @Override
  public List<String> listToxics(final ContainerContext ctx, final String proxyName)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    return executeApiCall(
        "Failed to list toxics for proxy: " + proxyName,
        () -> {
          final String url = String.format("%s/proxies/%s/toxics", apiUrl, proxyName);
          final ExecResult result =
              ctx.shell().exec(ctx.container(), ctx.http().buildGetRequest(url));

          if (result.getExitCode() != 0) {
            throw new IOException(
                String.format(
                    "Failed to list toxics for proxy '%s': %s", proxyName, result.getStderr()));
          }

          return parseToxicNames(result.getStdout());
        });
  }

  @Override
  public void addToxic(
      final ContainerContext ctx,
      final String proxyName,
      final String toxicName,
      final String toxicType,
      final String attributes,
      final double toxicity)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");
    Objects.requireNonNull(toxicType, "toxicType must not be null");
    Objects.requireNonNull(attributes, "attributes must not be null");
    validateToxicity(toxicity);

    executeApiCall(
        String.format("Failed to add toxic: %s/%s", proxyName, toxicName),
        () -> {
          final String url = String.format("%s/proxies/%s/toxics", apiUrl, proxyName);
          final String command =
              ctx.http()
                  .buildPostJsonRequest(
                      url, buildToxicJson(toxicName, toxicType, attributes, toxicity));
          final ExecResult result = ctx.shell().exec(ctx.container(), command);

          if (result.getExitCode() != 0) {
            throw new IOException(
                String.format("Failed to add toxic '%s': %s", toxicName, result.getStderr()));
          }

          log.debug("Added toxic '{}' to proxy '{}' via API", toxicName, proxyName);
        });
  }

  @Override
  public void deleteToxic(
      final ContainerContext ctx, final String proxyName, final String toxicName)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");

    executeApiCall(
        String.format("Failed to delete toxic: %s/%s", proxyName, toxicName),
        () -> {
          final String url = String.format("%s/proxies/%s/toxics/%s", apiUrl, proxyName, toxicName);
          final ExecResult result =
              ctx.shell().exec(ctx.container(), ctx.http().buildDeleteRequest(url));

          if (result.getExitCode() != 0) {
            throw new IOException(
                String.format(
                    "Failed to delete toxic '%s' from proxy '%s': %s",
                    toxicName, proxyName, result.getStderr()));
          }

          log.debug("Deleted toxic '{}' from proxy '{}' via API", toxicName, proxyName);
        });
  }

  // ==================== Private Helpers ====================

  /**
   * Execute an API call that returns a value, wrapping any non-{@link IOException} into one.
   *
   * <p>Centralizes the recurring pattern:
   *
   * <pre>
   *   try { ... }
   *   catch (IOException e) { throw e; }
   *   catch (Exception e)   { throw new IOException(message, e); }
   * </pre>
   *
   * @param <T> return type
   * @param errorMessage message used when wrapping a non-{@code IOException}
   * @param call the operation to execute
   * @return the operation result
   * @throws IOException if the call throws
   */
  private <T> T executeApiCall(final String errorMessage, final ReturningApiCall<T> call)
      throws IOException {
    try {
      return call.execute();
    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException(errorMessage, e);
    }
  }

  /**
   * Execute a void API call, wrapping any non-{@link IOException} into one.
   *
   * @param errorMessage message used when wrapping a non-{@code IOException}
   * @param call the operation to execute
   * @throws IOException if the call throws
   */
  private void executeApiCall(final String errorMessage, final VoidApiCall call)
      throws IOException {
    try {
      call.execute();
    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException(errorMessage, e);
    }
  }

  /**
   * Build JSON payload for proxy creation.
   *
   * @param config proxy configuration
   * @return JSON string
   */
  private String buildProxyJson(final ProxyConfiguration config) {
    return String.format(
        "{\"name\":\"%s\",\"listen\":\"0.0.0.0:%d\","
            + "\"upstream\":\"localhost:%d\",\"enabled\":true}",
        config.getProxyName(), config.getProxyPort(), config.getServicePort());
  }

  /**
   * Build JSON payload for toxic creation.
   *
   * @param toxicName toxic name
   * @param toxicType toxic type identifier (e.g., "latency", "timeout")
   * @param attributes toxic attributes as a JSON object string
   * @param toxicity application probability (0.0–1.0)
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
   * Parse toxic names from Toxiproxy JSON response array.
   *
   * <p>Extracts the {@code "name"} field of each toxic object using a regex to avoid introducing a
   * JSON library dependency.
   *
   * @param json raw JSON from {@code GET /proxies/{name}/toxics}
   * @return unmodifiable list of toxic names (empty when json is null or blank)
   */
  private List<String> parseToxicNames(final String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }

    return TOXIC_NAME_PATTERN.matcher(json).results().map(m -> m.group(1)).toList();
  }

  /**
   * Validate toxicity probability is within [0.0, 1.0].
   *
   * @param toxicity value to validate
   * @throws IllegalArgumentException if out of range
   */
  private void validateToxicity(final double toxicity) {
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  // ==================== Functional Interfaces ====================

  /**
   * A callable that returns a value and may throw any {@link Exception}.
   *
   * <p>Used with {@link #executeApiCall(String, ReturningApiCall)} to unify exception wrapping.
   *
   * @param <T> return type
   */
  @FunctionalInterface
  private interface ReturningApiCall<T> {
    T execute() throws Exception;
  }

  /**
   * A void callable that may throw any {@link Exception}.
   *
   * <p>Used with {@link #executeApiCall(String, VoidApiCall)} to unify exception wrapping.
   */
  @FunctionalInterface
  private interface VoidApiCall {
    void execute() throws Exception;
  }
}
