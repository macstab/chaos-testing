/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.lifecycle;

import java.io.IOException;

import com.macstab.chaos.toxiproxy.context.ContainerContext;

import lombok.NonNull;

/**
 * Contract for managing the Toxiproxy server process lifecycle inside a Testcontainers container.
 *
 * <h2>Why This Interface Exists</h2>
 *
 * <p>Toxiproxy is a standalone Go binary ({@code toxiproxy-server}) that must be installed, started
 * as a background process, and eventually stopped inside a running container. This lifecycle is
 * orthogonal to proxy CRUD operations and toxic management — separating it into a dedicated
 * interface enforces the Single Responsibility Principle and enables test doubles.
 *
 * <h2>Shared-Instance Design Constraint</h2>
 *
 * <p>One Toxiproxy process handles <em>all</em> proxies inside a single container — Toxiproxy is
 * not per-proxy, it is per-container. Multiple modules ({@code macstab-chaos-proxy}, {@code
 * macstab-chaos-connection}, {@code macstab-chaos-cache}) may each create their own proxies on the
 * same Toxiproxy instance within the same container. This has critical operational consequences:
 *
 * <ul>
 *   <li>{@link #ensureRunning(ContainerContext)} is called by each module independently. The
 *       implementation must be <strong>idempotent</strong> — if Toxiproxy is already running, the
 *       call must return immediately without side effects. This is enforced by checking {@link
 *       #isHealthy(ContainerContext)} first.
 *   <li>{@link #stop(ContainerContext)} terminates the Toxiproxy process and destroys <em>all</em>
 *       proxies registered by <em>all</em> modules. Calling stop from one module implicitly breaks
 *       all other modules' proxies on the same container. Callers must only invoke {@code stop()}
 *       during full container teardown ({@code @AfterAll}), never during per-test cleanup
 *       ({@code @AfterEach}).
 * </ul>
 *
 * <h2>Startup Sequence</h2>
 *
 * <p>The full startup path (when Toxiproxy is not already running):
 *
 * <ol>
 *   <li>Check {@link #isHealthy} — fast path, returns immediately if already running.
 *   <li>Delegate to {@link ToxiproxyInstaller#install} — downloads binary, installs dependencies if
 *       needed.
 *   <li>Launch {@code toxiproxy-server -host 0.0.0.0} as a background process via the container
 *       shell.
 *   <li>Poll {@link #isHealthy} until the API responds or the configured startup timeout expires.
 * </ol>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations are not required to be internally synchronized. Concurrent calls to {@link
 * #ensureRunning} from multiple threads on the same container may race during the startup sequence,
 * potentially launching multiple Toxiproxy processes. The second process will fail to bind port
 * 8474 (already in use) and exit. This is benign: the first process wins, and subsequent {@link
 * #isHealthy} calls will return {@code true}. However, concurrent callers should coordinate at a
 * higher level to avoid unnecessary process startup attempts.
 *
 * <h2>Failure Semantics</h2>
 *
 * <p>All methods that can fail declare {@code throws IOException}. The checked exception is
 * intentional: lifecycle failures (binary not downloadable, process crashed, container OOM) are
 * recoverable infrastructure conditions, not programming errors. Callers must handle them. The
 * timeout case ({@link #ensureRunning} exceeds the configured startup timeout) also throws {@link
 * IOException} — this is a change from an unchecked exception to ensure callers cannot silently
 * ignore startup failures.
 *
 * <h2>Default Implementation</h2>
 *
 * <p>{@link ToxiproxyLifecycleManager} is the production implementation. Inject a mock or stub for
 * testing via the 4-argument constructor of {@link
 * com.macstab.chaos.proxy.internal.ToxiproxyOrchestrator}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ToxiproxyLifecycleManager for production implementation
 * @see ToxiproxyInstaller for binary installation
 * @see com.macstab.chaos.toxiproxy.api.ToxiproxyApiClient for Toxiproxy REST operations
 */
public interface ToxiproxyLifecycle {

  /**
   * Ensures that the Toxiproxy server process is installed, running, and accepting API requests
   * inside the container. Idempotent — safe to call multiple times and from multiple modules.
   *
   * <p><strong>Contract and Idempotency:</strong> If {@link #isHealthy} returns {@code true} at
   * call time, this method returns immediately without side effects. This is the common fast path
   * once Toxiproxy is started. If {@link #isHealthy} returns {@code false}, the full startup
   * sequence executes: install → launch → poll-until-ready.
   *
   * <p><strong>Startup sequence steps:</strong>
   *
   * <ol>
   *   <li>Check {@link #isHealthy} — returns immediately if Toxiproxy is already responsive.
   *   <li>Invoke {@link ToxiproxyInstaller#install(ContainerContext)} — downloads the {@code
   *       toxiproxy-server} binary from GitHub releases if not already present in the container's
   *       PATH. Also installs {@code curl} and {@code iptables} as side effects.
   *   <li>Launch {@code toxiproxy-server -host 0.0.0.0 >/dev/null 2>&1 &} via the container shell.
   *       The process detaches and runs in the background.
   *   <li>Poll {@link #isHealthy} with interval {@code config.pollIntervalMs()} until either the
   *       API responds or {@code config.startupTimeoutMs()} milliseconds have elapsed.
   * </ol>
   *
   * <p><strong>Timeout behavior:</strong> If the API does not become ready within the configured
   * startup timeout, this method throws {@link IOException}. This typically indicates the binary
   * failed to execute (wrong architecture, missing libc), the container is resource-constrained
   * (OOM), or the binary could not download. The timeout is not precisely honored — polling uses
   * {@code Thread.sleep()}, which is subject to JVM scheduling jitter.
   *
   * <p><strong>InterruptedException handling:</strong> If the calling thread is interrupted during
   * the polling sleep, this method restores the interrupt flag via {@code
   * Thread.currentThread().interrupt()} and throws {@link IOException}. Callers that catch {@link
   * IOException} and continue executing should re-check {@code Thread.interrupted()} if
   * interrupt-driven cancellation is required.
   *
   * <p><strong>Performance:</strong> The fast path (already healthy) costs one Docker API round
   * trip (~5–100 ms). The cold path (binary not installed, process not started) may take 5–30
   * seconds depending on network speed (binary download) and container resources.
   *
   * @param ctx resolved container context; {@code ctx.container().isRunning()} must return {@code
   *     true} at call time
   * @throws IOException if binary installation fails, process launch fails, or the API does not
   *     become ready within the configured startup timeout
   * @throws IllegalStateException if the container is not running
   * @throws NullPointerException if ctx is null
   */
  void ensureRunning(@NonNull ContainerContext ctx) throws IOException;

  /**
   * Terminates the Toxiproxy server process and flushes all iptables NAT rules inside the
   * container. This is a <strong>terminal, destructive</strong> operation — every proxy registered
   * by every module (proxy, connection, cache) is destroyed, and all iptables redirects are
   * removed.
   *
   * <p><strong>CRITICAL — This is NOT per-module cleanup.</strong> {@code shutdown()} kills the
   * shared Toxiproxy process that serves all modules on the container. Calling this from one module
   * destroys every other module's active proxies and iptables rules. Use this <em>exclusively</em>
   * in {@code @AfterAll} when the container itself is being discarded. For per-module or per-test
   * cleanup, each module must use its own surgical {@code reset()} method (which removes only its
   * own proxies via the API, without touching the process or other modules' state).
   *
   * <p><strong>Shutdown sequence:</strong>
   *
   * <ol>
   *   <li>Flush all iptables NAT rules ({@code iptables -t nat -F}) via {@link
   *       com.macstab.chaos.toxiproxy.network.NetworkRedirect#clearAllRedirects}.
   *   <li>Kill the {@code toxiproxy-server} process via the platform's process command builder.
   * </ol>
   *
   * <p><strong>Post-shutdown state:</strong> {@link #isHealthy} returns {@code false}. All TCP
   * connections through Toxiproxy proxies are severed. All iptables NAT redirects are removed. A
   * subsequent call to {@link #ensureRunning} will re-install and re-start Toxiproxy from scratch —
   * but previously registered proxies and iptables rules are gone.
   *
   * <p><strong>Idempotency:</strong> Safe to call when Toxiproxy is not running or when no iptables
   * rules exist.
   *
   * @param ctx resolved container context; must reference a running container
   * @throws IOException if the kill or iptables command fails unexpectedly
   * @throws IllegalStateException if the container is not running
   * @throws NullPointerException if ctx is null
   */
  void shutdown(@NonNull ContainerContext ctx) throws IOException;

  /**
   * Tests whether the Toxiproxy management HTTP API is alive and responding, without throwing.
   *
   * <p><strong>Contract:</strong> Issues {@code GET /proxies} against the Toxiproxy API URL via the
   * container shell. Returns {@code true} if the shell command exits with code 0 (implying HTTP 200
   * response from Toxiproxy). Returns {@code false} for any failure condition and never throws.
   * This method is safe to call in tight polling loops.
   *
   * <p><strong>Why no exception:</strong> Health checks in polling loops are inherently optimistic
   * — the caller expects intermittent failures (process not yet started, brief unresponsiveness). A
   * health-check method that throws forces callers to wrap each call in try-catch, adding noise
   * without value. The {@code false} return is unambiguous: retry.
   *
   * <p><strong>What "healthy" means precisely:</strong> The Toxiproxy binary is executing, has
   * successfully bound port 8474, and its HTTP listener has processed at least one request (the
   * health check). It does NOT mean: (1) all previously registered proxies are still active
   * (Toxiproxy holds them in memory — they persist as long as the process runs), (2) TCP listeners
   * on proxy ports are accepting connections (they may take a few ms after proxy creation).
   *
   * <p><strong>Performance:</strong> One Docker API round trip per call (~5–100 ms). Do not call in
   * a tight loop with sub-millisecond interval — use {@code config.pollIntervalMs()} (default 100
   * ms) as the lower bound.
   *
   * @param ctx resolved container context; does NOT require the container to be running — returns
   *     {@code false} immediately if {@code ctx.container().isRunning()} is false
   * @return {@code true} if Toxiproxy's management API is responding; {@code false} for any failure
   *     including container stopped, process not running, or transient error
   */
  boolean isHealthy(@NonNull ContainerContext ctx);
}
