/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.api;

import java.io.IOException;
import java.util.List;

import com.macstab.chaos.toxiproxy.config.ProxyConfiguration;
import com.macstab.chaos.toxiproxy.context.ContainerContext;

import lombok.NonNull;

/**
 * HTTP client contract for the Toxiproxy management API running inside a container's network
 * namespace.
 *
 * <h2>Why This Interface Exists</h2>
 *
 * <p>Toxiproxy binds its management API to {@code 0.0.0.0:8474} <em>within</em> the container's
 * network namespace (Linux network namespace, isolated from the Docker host). This port is
 * deliberately not exported via Docker's port mapping mechanism. Consequently, a standard HTTP
 * client running in the test JVM (which operates in the host network namespace) cannot reach {@code
 * localhost:8474} — that address resolves to the host's loopback interface, not the container's.
 * The only way to reach Toxiproxy's API is to execute HTTP commands <em>inside</em> the container,
 * where {@code localhost:8474} resolves correctly. This interface abstracts that shell-based HTTP
 * dispatch, hiding the container-side execution model from callers.
 *
 * <h2>Architectural Role</h2>
 *
 * <p>This interface forms the lowest-level API boundary in the toxi-core module. It maps one-to-one
 * to the Toxiproxy REST API surface ({@code /proxies}, {@code /proxies/{name}/toxics}) and is the
 * only component in the module that constructs raw HTTP command strings. All higher-level
 * components — {@link com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycle}, {@link
 * com.macstab.chaos.proxy.internal.operations.ProxyOperations}, {@link
 * com.macstab.chaos.proxy.internal.operations.ToxicOperations} — depend on this interface, never on
 * Toxiproxy's REST wire format directly.
 *
 * <h2>Command Execution Model</h2>
 *
 * <p>All implementations must execute HTTP calls by running platform-appropriate HTTP commands
 * (e.g., {@code curl}) inside the target container via the {@link ContainerContext#shell()} and
 * {@link ContainerContext#http()} collaborators. No direct TCP connections from the test JVM to any
 * Toxiproxy port. The call path is:
 *
 * <pre>
 * Test JVM
 *   → ContainerContext.shell().exec(container, httpCommandString)
 *       → Docker daemon execInContainer API (HTTP/1.1 multiplexed stream)
 *           → container shell (bash/sh/busybox)
 *               → curl http://localhost:8474/proxies
 *                   → Toxiproxy REST API
 *                       → response stdout → ExecResult → caller
 * </pre>
 *
 * <p>Each call incurs at least one Docker API round trip (~5–50 ms depending on host load and
 * Docker Desktop vs native Linux Docker). Operations that combine multiple API calls ({@link
 * #proxyExists} then {@link #createProxy}) are not atomic; the proxy state can change between the
 * two calls.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be <strong>stateless and thread-safe</strong>. {@code ContainerContext}
 * is immutable and may be passed concurrently. However, callers must understand that Toxiproxy's
 * internal state machine is not protected by this interface: two concurrent {@link #createProxy}
 * calls for the same proxy name will result in a Toxiproxy API error on the second call (proxy
 * already exists). Coordination at a higher level is the caller's responsibility.
 *
 * <h2>Security Notes</h2>
 *
 * <p>Implementations that construct JSON payloads using {@code String.format()} without a JSON
 * library (the production case) are vulnerable to injection if proxy names or toxic names contain
 * special characters ({@code "}, {@code \}, etc.). Proxy names must be restricted to alphanumeric
 * characters and hyphens by callers. The {@code ToxiproxyApiClientImpl} does not validate or
 * sanitize names.
 *
 * <h2>Stable Contract</h2>
 *
 * <p>This interface is a stable internal API. {@link com.macstab.chaos.proxy.ProxyChaosProvider},
 * {@link com.macstab.chaos.connection.ToxiproxyConnectionChaos}, and test implementations depend on
 * it. Method signatures may not change in a backwards-incompatible way without a major version
 * bump.
 *
 * <h2>Non-Goals</h2>
 *
 * <ul>
 *   <li>This interface does not manage Toxiproxy lifecycle (install, start, stop) — see {@link
 *       com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycle}.
 *   <li>This interface does not manage iptables redirect rules — see {@link
 *       com.macstab.chaos.toxiproxy.network.NetworkRedirect}.
 *   <li>This interface does not implement retry logic for transient failures.
 * </ul>
 *
 * <h2>Reference</h2>
 *
 * <p>Toxiproxy REST API: https://github.com/Shopify/toxiproxy — the {@code /proxies} and {@code
 * /proxies/{name}/toxics} endpoints are the only surfaces used by this interface.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycle for process lifecycle
 * @see com.macstab.chaos.toxiproxy.network.NetworkRedirect for iptables management
 * @see ToxiproxyApiClientImpl for the production implementation
 */
public interface ToxiproxyApiClient {

  /**
   * Tests whether the Toxiproxy management API is alive and responding inside the container.
   *
   * <p><strong>Contract:</strong> Issues {@code GET /proxies} via a shell command inside the
   * container. Returns {@code true} iff the command exits with code 0 (implying Toxiproxy returned
   * HTTP 200). Returns {@code false} for any failure — process not running, container stopped,
   * shell execution error, non-zero HTTP status — and never propagates an exception.
   *
   * <p><strong>Why no exception:</strong> This method is called in polling loops during Toxiproxy
   * startup (see {@link com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycleManager}). A
   * polling loop that must catch exceptions adds significant noise; returning {@code false} on any
   * failure makes the loop trivially safe. The tradeoff is that transient infrastructure errors
   * (Docker daemon hiccup, container OOM) are indistinguishable from "not yet started" — callers
   * accept this ambiguity in exchange for simpler loop code.
   *
   * <p><strong>Performance:</strong> Each call incurs one Docker API round trip. In a polling loop
   * with 100 ms interval, this adds ~100–150 ms per iteration on native Linux Docker. On Docker
   * Desktop (macOS/Windows), expect 200–400 ms per iteration due to VM overhead.
   *
   * @param ctx resolved container context; must reference a running container
   * @return {@code true} if Toxiproxy's management API responded with HTTP 200; {@code false} for
   *     any error including container stopped, process not running, or network failure
   */
  boolean isApiReady(@NonNull ContainerContext ctx);

  /**
   * Checks whether a named proxy entry exists in Toxiproxy's registry.
   *
   * <p><strong>Contract:</strong> Issues {@code GET /proxies/{proxyName}}. Returns {@code true} if
   * Toxiproxy responds with HTTP 200 (proxy found). Returns {@code false} for HTTP 404 (proxy not
   * found). Throws {@link IOException} for unexpected errors (e.g., Toxiproxy process crashed
   * mid-call, Docker exec failure).
   *
   * <p><strong>What "exists" means:</strong> A proxy entry exists in Toxiproxy's in-memory
   * registry. This does not guarantee that the proxy's TCP listener is currently accepting
   * connections — the proxy might be in a transitional state immediately after creation. If
   * listener readiness is required, use proxy-readiness polling at a higher level.
   *
   * <p><strong>TOCTOU risk:</strong> The result is immediately stale. A proxy that exists at call
   * time may be deleted before the caller acts on the result. Callers that require create-or-skip
   * semantics must not assume the result remains valid.
   *
   * @param ctx resolved container context; must reference a running container with Toxiproxy active
   * @param proxyName the Toxiproxy proxy name to check; must match the name used in {@link
   *     #createProxy}; must not contain characters that break shell command interpolation (restrict
   *     to {@code [a-zA-Z0-9_-]})
   * @return {@code true} if a proxy with this name is registered in Toxiproxy; {@code false} if it
   *     does not exist (HTTP 404)
   * @throws IOException if the shell command fails unexpectedly, or if Toxiproxy returns an error
   *     other than HTTP 404
   * @throws NullPointerException if ctx or proxyName is null
   */
  boolean proxyExists(@NonNull ContainerContext ctx, @NonNull String proxyName) throws IOException;

  /**
   * Creates a new proxy entry in Toxiproxy, registering the TCP interception configuration.
   *
   * <p><strong>Contract:</strong> Issues {@code POST /proxies} with a JSON payload derived from
   * {@code config}. On success, Toxiproxy immediately begins listening on {@code proxyPort} and
   * forwarding to {@code servicePort} at {@code localhost} inside the container. Throws {@link
   * IOException} if Toxiproxy rejects the request (e.g., duplicate proxy name, invalid port,
   * process not running) or if the Docker exec call fails.
   *
   * <p><strong>JSON payload structure:</strong>
   *
   * <pre>
   * {
   *   "name":     "redis",
   *   "listen":   "0.0.0.0:16379",
   *   "upstream": "localhost:6379",
   *   "enabled":  true
   * }
   * </pre>
   *
   * <p><strong>Upstream address:</strong> Always {@code localhost:{servicePort}} — Toxiproxy
   * forwards to the service on the same container's loopback. This is correct because both
   * Toxiproxy and the real service (Redis, Postgres, etc.) run in the same container.
   *
   * <p><strong>Listen address:</strong> Always {@code 0.0.0.0:{proxyPort}} — Toxiproxy binds on all
   * interfaces so that iptables PREROUTING redirected traffic can reach it regardless of source
   * interface.
   *
   * <p><strong>Not idempotent:</strong> Calling this method with a name that already exists in
   * Toxiproxy's registry will fail with an error from Toxiproxy. Callers must check {@link
   * #proxyExists} first or use higher-level orchestration that handles idempotency.
   *
   * <p><strong>Listener readiness:</strong> Toxiproxy starts listening asynchronously after the API
   * call returns. A brief delay (typically a few milliseconds) may exist before the proxy port
   * actually accepts connections. Callers requiring immediate connectivity must poll.
   *
   * @param ctx resolved container context; must reference a running container with Toxiproxy active
   * @param config complete proxy configuration including name, service port, proxy port, and
   *     container hostname; see {@link ProxyConfiguration}
   * @throws IOException if proxy creation fails — possible causes: duplicate name, port conflict,
   *     Toxiproxy process crashed, invalid JSON payload, Docker exec failure
   * @throws NullPointerException if ctx or config is null
   */
  void createProxy(@NonNull ContainerContext ctx, @NonNull ProxyConfiguration config)
      throws IOException;

  /**
   * Deletes a proxy entry from Toxiproxy, stopping its TCP listener and removing all attached
   * toxics.
   *
   * <p><strong>Contract:</strong> Issues {@code DELETE /proxies/{proxyName}}. On success, Toxiproxy
   * immediately stops listening on the proxy's port and removes all toxic configurations from its
   * in-memory registry. Throws {@link IOException} if the command fails.
   *
   * <p><strong>Toxics are removed atomically:</strong> Toxiproxy removes all toxics associated with
   * the proxy as part of proxy deletion. Callers do not need to remove toxics individually before
   * calling this method.
   *
   * <p><strong>iptables rules are NOT removed:</strong> This method only removes the Toxiproxy
   * proxy entry. Any iptables PREROUTING/OUTPUT rules that redirect traffic to the now-deleted
   * proxy port remain active and will cause connection resets for affected traffic. Callers must
   * also invoke {@link
   * com.macstab.chaos.toxiproxy.network.NetworkRedirect#removeRedirect(ContainerContext, int, int)}
   * to restore normal traffic flow. Higher-level orchestration (e.g., {@link
   * com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator#deleteProxy}) handles both steps
   * together.
   *
   * @param ctx resolved container context; must reference a running container with Toxiproxy active
   * @param proxyName the name of the proxy to delete; must match an existing proxy name
   * @throws IOException if deletion fails — possible causes: proxy does not exist (HTTP 404),
   *     Toxiproxy process crashed, Docker exec failure
   * @throws NullPointerException if ctx or proxyName is null
   */
  void deleteProxy(@NonNull ContainerContext ctx, @NonNull String proxyName) throws IOException;

  /**
   * Tests whether a named toxic is currently active on a given proxy.
   *
   * <p><strong>Contract:</strong> Fetches the toxic list via {@code GET
   * /proxies/{proxyName}/toxics} and performs a name-based membership check. Returns {@code false}
   * on any error rather than propagating, making it safe for conditional checks. Throws {@link
   * IOException} only for infrastructure failures (Docker exec failure, process not running).
   *
   * <p><strong>Implementation note:</strong> This method delegates to {@link #listToxics} and
   * performs a linear scan. For proxies with many toxics (unusual but possible), this is O(n). In
   * typical test scenarios a proxy has 1–3 toxics; the cost is negligible.
   *
   * @param ctx resolved container context
   * @param proxyName the proxy to check
   * @param toxicName the toxic name to search for
   * @return {@code true} if a toxic with this name exists on the proxy; {@code false} if not found
   *     or if any non-infrastructure error occurs
   * @throws IOException if the underlying API call fails at the infrastructure level
   * @throws NullPointerException if any argument is null
   */
  boolean toxicExists(
      @NonNull ContainerContext ctx, @NonNull String proxyName, @NonNull String toxicName)
      throws IOException;

  /**
   * Returns the names of all toxics currently active on a proxy.
   *
   * <p><strong>Contract:</strong> Issues {@code GET /proxies/{proxyName}/toxics}. Parses the JSON
   * array response and extracts all {@code "name"} fields. Returns an empty, unmodifiable list if
   * no toxics are active. Throws {@link IOException} if the proxy does not exist or if the command
   * fails.
   *
   * <p><strong>Parsing strategy:</strong> The production implementation uses a regex against the
   * raw JSON string rather than a full JSON parser. This is an intentional dependency trade-off:
   * adding a JSON library (Jackson, Gson) for a single parse operation in a test framework is
   * disproportionate. The regex is robust for well-formed Toxiproxy responses but will misparse
   * malformed or unexpected JSON structures. Toxiproxy's response format is stable across versions
   * 2.x.
   *
   * <p><strong>Snapshot semantics:</strong> The returned list reflects the state at the time of the
   * API call. Toxics added or removed concurrently are not reflected.
   *
   * @param ctx resolved container context
   * @param proxyName the proxy whose toxics to list; must exist in Toxiproxy
   * @return unmodifiable list of toxic names; empty if no toxics are active
   * @throws IOException if the proxy does not exist, Toxiproxy is not running, or the Docker exec
   *     call fails
   * @throws NullPointerException if ctx or proxyName is null
   */
  List<String> listToxics(@NonNull ContainerContext ctx, @NonNull String proxyName)
      throws IOException;

  /**
   * Adds a fault-injection toxic to a proxy, beginning immediate effect on affected connections.
   *
   * <p><strong>Contract:</strong> Issues {@code POST /proxies/{proxyName}/toxics} with a JSON
   * payload constructed from the provided parameters. On success, Toxiproxy begins applying the
   * fault to new and existing connections (depending on toxic type) immediately. Throws {@link
   * IOException} on failure.
   *
   * <p><strong>JSON payload structure:</strong>
   *
   * <pre>
   * {
   *   "name":       "{toxicName}",
   *   "type":       "{toxicType}",
   *   "attributes": {attributes},
   *   "toxicity":   {toxicity}
   * }
   * </pre>
   *
   * <p>The {@code attributes} value must be a JSON object string produced by {@link
   * com.macstab.chaos.toxiproxy.toxic.ToxicConfig#toJson()}. The outer envelope ({@code name},
   * {@code type}, {@code toxicity}) is assembled by the API client, not by the toxic model. This
   * separation enforces that the toxic model only knows its domain-specific parameters while the
   * API client knows the wire format.
   *
   * <p><strong>Duplicate name:</strong> Adding a toxic whose name already exists on the proxy will
   * fail with a Toxiproxy API error. Callers must ensure name uniqueness per proxy, or use {@link
   * #toxicExists} to guard the call.
   *
   * <p><strong>Toxicity semantics:</strong> {@code toxicity} controls the fraction of connections
   * affected. Toxiproxy implements this as a Bernoulli trial per connection: each new connection
   * independently has probability {@code toxicity} of being affected. This is probabilistic, not
   * guaranteed — 10 connections with {@code toxicity=0.5} may all be affected or none may be.
   *
   * @param ctx resolved container context
   * @param proxyName the proxy on which to install the toxic
   * @param toxicName unique name for this toxic within the proxy
   * @param toxicType Toxiproxy type identifier: {@code "latency"}, {@code "timeout"}, {@code
   *     "bandwidth"}, {@code "slow_close"}, {@code "limit_data"}, or {@code "down"}
   * @param attributes toxic-specific JSON object string (attributes only, no outer envelope);
   *     produced by {@link com.macstab.chaos.toxiproxy.toxic.ToxicConfig#toJson()}
   * @param toxicity fraction of connections to affect; must be in [0.0, 1.0]
   * @throws IOException if the API call fails — possible causes: proxy not found, duplicate toxic
   *     name, invalid type, Toxiproxy process not running, Docker exec failure
   * @throws IllegalArgumentException if toxicity is outside [0.0, 1.0]
   * @throws NullPointerException if any non-primitive argument is null
   */
  void addToxic(
      @NonNull ContainerContext ctx,
      @NonNull String proxyName,
      @NonNull String toxicName,
      @NonNull String toxicType,
      @NonNull String attributes,
      double toxicity)
      throws IOException;

  /**
   * Removes a specific toxic from a proxy, restoring normal behavior for connections previously
   * affected by it.
   *
   * <p><strong>Contract:</strong> Issues {@code DELETE /proxies/{proxyName}/toxics/{toxicName}}. On
   * success, the proxy continues forwarding traffic but the removed fault is no longer applied to
   * new connections. In-flight connections that were already affected by the toxic may still
   * experience the fault until they complete or are closed (depending on toxic type — latency
   * toxics, for example, affect data already buffered).
   *
   * <p><strong>Per-proxy scope:</strong> Toxic names are scoped to their proxy. The same toxic name
   * may exist on different proxies simultaneously without conflict.
   *
   * @param ctx resolved container context
   * @param proxyName the proxy from which to remove the toxic
   * @param toxicName the name of the toxic to remove
   * @throws IOException if the toxic or proxy does not exist, or the Docker exec call fails
   * @throws NullPointerException if any argument is null
   */
  void deleteToxic(
      @NonNull ContainerContext ctx, @NonNull String proxyName, @NonNull String toxicName)
      throws IOException;
}
