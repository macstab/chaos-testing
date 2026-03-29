/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.api;

import java.io.IOException;
import java.util.List;

import com.macstab.chaos.proxy.internal.ContainerContext;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;

/**
 * HTTP client for the Toxiproxy management API running inside a container.
 *
 * <h2>Why Shell-Based HTTP</h2>
 *
 * <p>Toxiproxy binds to {@code 0.0.0.0:8474} <em>inside</em> the container's network namespace.
 * The port is not exposed to the Docker host, so the test JVM cannot reach it via a standard
 * HTTP client. Instead, all API calls are executed by running HTTP commands (via
 * {@link com.macstab.chaos.core.command.http.HttpCommandBuilder}) inside the container through
 * the shell, which has access to localhost within the container's namespace.
 *
 * <h2>Command Execution Model</h2>
 *
 * <pre>
 * Test JVM
 *   → shell.exec(container, "curl -s http://localhost:8474/proxies")
 *       → runs inside container namespace
 *           → Toxiproxy API responds
 * </pre>
 *
 * <h2>Context Passing</h2>
 *
 * <p>All methods receive a pre-resolved {@link ContainerContext}. The HTTP command string is
 * built by {@link ContainerContext#http()} (platform-appropriate
 * {@link com.macstab.chaos.core.command.http.HttpCommandBuilder}) and executed via
 * {@link ContainerContext#shell()}. No direct {@code execInContainer} calls. No hardcoded
 * tool names.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be stateless and thread-safe. {@link ToxiproxyApiClientImpl} holds
 * only the immutable {@code apiUrl} string and satisfies this requirement.
 *
 * <h2>Default Implementation</h2>
 *
 * <p>{@link ToxiproxyApiClientImpl} is the production implementation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ToxiproxyApiClient {

  /**
   * Check whether the Toxiproxy HTTP API is alive and responding.
   *
   * <p>Issues {@code GET /proxies} and checks the exit code. Returns {@code true} if exit
   * code is 0 (HTTP 200). Returns {@code false} for any failure (process not running, network
   * error, non-zero exit) without throwing. Safe to call in a polling loop.
   *
   * @param ctx resolved container context
   * @return {@code true} if the API responded successfully, {@code false} otherwise
   */
  boolean isApiReady(ContainerContext ctx);

  /**
   * Check whether a named proxy entry exists in Toxiproxy.
   *
   * <p>Issues {@code GET /proxies/{proxyName}}. Returns {@code true} if exit code is 0
   * (HTTP 200 — proxy found). Returns {@code false} for HTTP 404 (proxy not found).
   *
   * <p>Note: existence does not imply the proxy port is currently listening — check that
   * separately if readiness matters.
   *
   * @param ctx resolved container context
   * @param proxyName proxy name (Toxiproxy API key)
   * @return {@code true} if the proxy exists
   * @throws IOException if the shell command fails with an unexpected error (not HTTP 404)
   * @throws NullPointerException if ctx or proxyName is null
   */
  boolean proxyExists(ContainerContext ctx, String proxyName) throws IOException;

  /**
   * Create a new proxy entry in Toxiproxy.
   *
   * <p>Issues {@code POST /proxies} with the proxy configuration as JSON:
   * <pre>
   * {"name":"redis","listen":"0.0.0.0:16379","upstream":"localhost:6379","enabled":true}
   * </pre>
   *
   * <p>The proxy immediately begins listening on {@code proxyPort} and forwarding to
   * {@code servicePort} on localhost — though it may take a few milliseconds to become ready.
   * Use {@link com.macstab.chaos.proxy.internal.operations.ProxyOperations#createProxy} for
   * the full creation flow including readiness polling.
   *
   * @param ctx resolved container context
   * @param config proxy configuration (name, service port, proxy port, hostname)
   * @throws IOException if the API returns a non-zero exit code or the shell command fails
   * @throws NullPointerException if ctx or config is null
   */
  void createProxy(ContainerContext ctx, ProxyConfiguration config) throws IOException;

  /**
   * Delete a proxy entry from Toxiproxy.
   *
   * <p>Issues {@code DELETE /proxies/{proxyName}}. All toxics attached to the proxy are
   * automatically removed by Toxiproxy. The proxy stops listening on its configured port
   * immediately.
   *
   * <p>Note: this does not remove iptables redirects — those must be cleaned up separately via
   * {@link com.macstab.chaos.proxy.network.NetworkRedirect#clearAllRedirects(ContainerContext)}.
   *
   * @param ctx resolved container context
   * @param proxyName proxy name to delete
   * @throws IOException if the API returns a non-zero exit code or the shell command fails
   * @throws NullPointerException if ctx or proxyName is null
   */
  void deleteProxy(ContainerContext ctx, String proxyName) throws IOException;

  /**
   * Check whether a named toxic exists on a proxy.
   *
   * <p>Fetches the toxic list via {@code GET /proxies/{proxyName}/toxics} and searches for
   * a toxic entry with a matching {@code "name"} field. Returns {@code false} on any error.
   *
   * @param ctx resolved container context
   * @param proxyName proxy name
   * @param toxicName toxic name to search for
   * @return {@code true} if a toxic with the given name exists
   * @throws IOException if the API call fails
   * @throws NullPointerException if any argument is null
   */
  boolean toxicExists(ContainerContext ctx, String proxyName, String toxicName) throws IOException;

  /**
   * List the names of all toxics currently active on a proxy.
   *
   * <p>Issues {@code GET /proxies/{proxyName}/toxics} and extracts the {@code "name"} field
   * from each entry in the JSON array response. Parses the response with a regex rather than
   * a full JSON parser to avoid adding a JSON library dependency.
   *
   * @param ctx resolved container context
   * @param proxyName proxy name
   * @return unmodifiable list of toxic names; empty if no toxics are active
   * @throws IOException if the API call fails or the proxy does not exist
   * @throws NullPointerException if ctx or proxyName is null
   */
  List<String> listToxics(ContainerContext ctx, String proxyName) throws IOException;

  /**
   * Add a toxic to a proxy.
   *
   * <p>Issues {@code POST /proxies/{proxyName}/toxics} with the following JSON payload:
   * <pre>
   * {
   *   "name":       "{toxicName}",
   *   "type":       "{toxicType}",
   *   "attributes": {attributes},
   *   "toxicity":   {toxicity}
   * }
   * </pre>
   *
   * <p>The {@code attributes} string must be a valid JSON object matching the Toxiproxy
   * schema for the given type (e.g., {@code {"latency":100,"jitter":0}} for type
   * {@code "latency"}). It is produced by {@link com.macstab.chaos.proxy.internal.operations.toxic.ToxicConfig#toJson()}.
   *
   * @param ctx resolved container context
   * @param proxyName proxy name
   * @param toxicName unique toxic name within this proxy
   * @param toxicType Toxiproxy type identifier (e.g., {@code "latency"}, {@code "timeout"})
   * @param attributes toxic-specific attributes as a JSON object string
   * @param toxicity fraction of connections to affect (0.0–1.0)
   * @throws IOException if the API returns a non-zero exit code or the shell command fails
   * @throws IllegalArgumentException if toxicity is outside [0.0, 1.0]
   * @throws NullPointerException if any non-primitive argument is null
   */
  void addToxic(
      ContainerContext ctx,
      String proxyName,
      String toxicName,
      String toxicType,
      String attributes,
      double toxicity)
      throws IOException;

  /**
   * Delete a specific toxic from a proxy.
   *
   * <p>Issues {@code DELETE /proxies/{proxyName}/toxics/{toxicName}}. After deletion, the
   * proxy continues forwarding traffic but the removed fault is no longer applied.
   *
   * @param ctx resolved container context
   * @param proxyName proxy name
   * @param toxicName name of the toxic to delete
   * @throws IOException if the API returns a non-zero exit code or the shell command fails
   * @throws NullPointerException if any argument is null
   */
  void deleteToxic(ContainerContext ctx, String proxyName, String toxicName) throws IOException;
}
