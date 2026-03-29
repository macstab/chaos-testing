/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Cache chaos injection SPI for in-memory cache backends (Redis, Hazelcast, Memcached, …).
 *
 * <p>Injects TCP-level faults via Toxiproxy and data-level faults via backend-specific CLI.
 * No application code changes required — all chaos is applied transparently at the network
 * and data layer.
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Backend Implementations</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <table border="1">
 *   <caption>Available Implementations</caption>
 *   <tr><th>Backend</th><th>Class</th><th>Module</th></tr>
 *   <tr>
 *     <td>Redis</td>
 *     <td>{@code RedisCacheChaosProvider}</td>
 *     <td>{@code macstab-chaos-cache}</td>
 *   </tr>
 *   <tr>
 *     <td>Hazelcast</td>
 *     <td>{@code HazelcastCacheChaosProvider} (planned)</td>
 *     <td>{@code macstab-chaos-cache}</td>
 *   </tr>
 *   <tr>
 *     <td>Memcached</td>
 *     <td>{@code MemcachedCacheChaosProvider} (planned)</td>
 *     <td>{@code macstab-chaos-cache}</td>
 *   </tr>
 * </table>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>⚠️ CRITICAL: Lifecycle — targeted reset vs nuclear reset</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p>Implementations that use Toxiproxy share a single Toxiproxy process with other modules
 * on the same container. Cleanup must be done carefully:
 *
 * <ul>
 *   <li>✅ {@link #reset} on this interface is <strong>targeted</strong>: only the cache proxy
 *       is removed. The Toxiproxy process and all other proxies stay alive.
 *       Use in {@code @AfterEach}.</li>
 *   <li>⛔ <strong>Nuclear reset</strong> (kills Toxiproxy + ALL iptables rules) must be done
 *       via the underlying {@code ProxyChaosProvider.reset(container)} in {@code @AfterAll}.</li>
 * </ul>
 *
 * <pre>{@code
 * // ✅ CORRECT — per-test: remove only cache proxy
 * @AfterEach
 * void cleanup() {
 *     cacheChaos.reset(container);         // removes "redis_cache" proxy only
 * }
 *
 * // ✅ CORRECT — container is done, nuke everything
 * @AfterAll
 * static void teardown() {
 *     proxyChaos.reset(container);         // kills Toxiproxy + all iptables rules
 * }
 * }</pre>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Requirements</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p><strong>NET_ADMIN capability</strong> required for iptables port redirect:
 *
 * <pre>{@code
 * .withCreateContainerCmdModifier(cmd ->
 *     cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN))
 * }</pre>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Quick Start (Redis)</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>{@code
 * @Testcontainers
 * class RedisChaosTest {
 *
 *   @Container
 *   static GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
 *       .withExposedPorts(6379)
 *       .withCreateContainerCmdModifier(cmd ->
 *           cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
 *
 *   CacheChaos chaos = new RedisCacheChaosProvider();
 *
 *   @AfterEach
 *   void cleanup() {
 *       chaos.reset(redis);  // surgical — removes only Redis proxy
 *   }
 *
 *   @Test
 *   void shouldHandleSlowCache() {
 *       chaos.slowResponse(redis, Duration.ofMillis(200));
 *       // ... assert application handles slow cache
 *   }
 *
 *   @Test
 *   void shouldHandleConnectionFailures() {
 *       chaos.injectConnectionFailures(redis, 0.3);  // 30% drop rate
 *       // ... test retry / circuit breaker logic
 *   }
 * }
 * }</pre>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Fault Categories</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p><strong>TCP-level faults</strong> (via Toxiproxy — transparent to the cache backend):
 * <ul>
 *   <li>{@link #slowResponse} — add fixed latency to all operations</li>
 *   <li>{@link #injectConnectionFailures} — drop TCP connections probabilistically</li>
 *   <li>{@link #limitThroughput} — cap transfer rate (simulates slow network)</li>
 *   <li>{@link #truncateResponses} — close connection mid-response (broken protocol framing)</li>
 *   <li>{@link #removeFault} — remove a named fault</li>
 *   <li>{@link #removeAllFaults} — remove all faults, proxy stays active</li>
 * </ul>
 *
 * <p><strong>Data-level faults</strong> (via backend CLI — bypass proxy):
 * <ul>
 *   <li>{@link #forceEviction} — delete percentage of keys (simulate eviction pressure)</li>
 *   <li>{@link #limitMemory} — set maxmemory limit (trigger real eviction)</li>
 *   <li>{@link #setEvictionPolicy} — change eviction policy</li>
 *   <li>{@link #disconnectClients} — kill all connected clients</li>
 *   <li>{@link #flushAll} — wipe entire cache (use in teardown)</li>
 * </ul>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>TCP Fault Names</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p>TCP faults are registered in Toxiproxy with fixed names. Use these names with
 * {@link #removeFault}:
 *
 * <table border="1">
 *   <caption>Fault Names</caption>
 *   <tr><th>Method</th><th>Toxiproxy name</th></tr>
 *   <tr><td>{@link #slowResponse}</td><td>{@code "latency"}</td></tr>
 *   <tr><td>{@link #injectConnectionFailures}</td><td>{@code "timeout"}</td></tr>
 *   <tr><td>{@link #limitThroughput}</td><td>{@code "bandwidth"}</td></tr>
 *   <tr><td>{@link #truncateResponses}</td><td>{@code "limit_data"}</td></tr>
 * </table>
 *
 * <pre>{@code
 * chaos.slowResponse(container, Duration.ofMillis(200));   // creates "latency" toxic
 * chaos.removeFault(container, "latency");                 // removes it
 * }</pre>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Implementation</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-cache:1.0.0")
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface CacheChaos extends ChaosProvider {

  // ==================== TCP-Level Faults (via Toxiproxy) ====================

  /**
   * Add fixed latency to all cache operations.
   *
   * <p>Simulates slow networks or a heavily loaded cache backend.
   * Creates a {@code "latency"} toxic in Toxiproxy.
   *
   * <pre>{@code
   * chaos.slowResponse(container, Duration.ofMillis(200));   // 200ms on every op
   * }</pre>
   *
   * @param container target container
   * @param delay     latency to add (must not be null or negative)
   */
  void slowResponse(GenericContainer<?> container, Duration delay);

  /**
   * Drop TCP connections to the cache probabilistically.
   *
   * <p>Simulates flaky networks, intermittent cache outages, or network partitions.
   * Creates a {@code "timeout"} toxic (instant close) in Toxiproxy.
   *
   * <p><strong>Note:</strong> This is a TCP-level connection drop — not a protocol-level
   * cache miss. Clients observe a connection exception, not a null/miss return value.
   *
   * <pre>{@code
   * chaos.injectConnectionFailures(container, 0.3);  // 30% of connections dropped
   * }</pre>
   *
   * @param container target container
   * @param rate      drop probability in [0.0, 1.0] (e.g., 0.3 = 30% of connections dropped)
   * @throws IllegalArgumentException if rate is not in [0.0, 1.0]
   */
  void injectConnectionFailures(GenericContainer<?> container, double rate);

  /**
   * Cap TCP transfer rate through the proxy.
   *
   * <p>Simulates a bandwidth-constrained network link or a slow cache response path.
   * Creates a {@code "bandwidth"} toxic in Toxiproxy.
   *
   * <pre>{@code
   * chaos.limitThroughput(container, 10);  // 10 KB/s
   * }</pre>
   *
   * @param container target container
   * @param rateKBps  bandwidth limit in kilobytes per second (must be &gt; 0)
   * @throws IllegalArgumentException if rateKBps is not positive
   */
  void limitThroughput(GenericContainer<?> container, long rateKBps);

  /**
   * Close the TCP connection after a fixed number of bytes (truncate mid-response).
   *
   * <p>Injects broken protocol framing — the client receives a partial response and must
   * handle the truncation gracefully. Creates a {@code "limit_data"} toxic in Toxiproxy.
   *
   * <pre>{@code
   * chaos.truncateResponses(container, 1024);  // close after 1 KB
   * chaos.truncateResponses(container, 0);     // close immediately on first data
   * }</pre>
   *
   * @param container target container
   * @param bytes     byte threshold at which the connection is closed (0 = instant on first data)
   * @throws IllegalArgumentException if bytes is negative
   */
  void truncateResponses(GenericContainer<?> container, long bytes);

  /**
   * Remove a named TCP fault from the proxy.
   *
   * <p>No-op if the fault does not exist. See fault name table in class Javadoc.
   *
   * @param container target container
   * @param faultName Toxiproxy toxic name to remove
   */
  void removeFault(GenericContainer<?> container, String faultName);

  /**
   * Remove all TCP faults from the proxy.
   *
   * <p>Restores normal throughput and latency without removing the proxy itself.
   * Use {@link #reset} to delete the proxy entirely.
   *
   * @param container target container
   */
  void removeAllFaults(GenericContainer<?> container);

  // ==================== Data-Level Faults (via backend CLI) ====================

  /**
   * Force-evict a percentage of all keys.
   *
   * <p>Simulates eviction pressure — the application's cache hit ratio drops immediately.
   * Operates directly on the backend (bypasses the proxy) so it works under active TCP faults.
   *
   * <pre>{@code
   * chaos.forceEviction(container, 50);   // delete ~50% of keys
   * }</pre>
   *
   * @param container  target container
   * @param percentage percentage of keys to evict (1–100)
   * @throws IllegalArgumentException if percentage is not in [1, 100]
   */
  void forceEviction(GenericContainer<?> container, int percentage);

  /**
   * Set the cache memory limit to trigger real backend eviction.
   *
   * <p>Combine with {@link #setEvictionPolicy} for full control.
   *
   * <pre>{@code
   * chaos.limitMemory(container, 64 * 1024 * 1024L);  // 64 MB limit
   * chaos.setEvictionPolicy(container, "allkeys-lru"); // evict least-recently used
   * }</pre>
   *
   * @param container target container
   * @param bytes     memory limit in bytes (0 = remove limit, restore default)
   * @throws IllegalArgumentException if bytes is negative
   */
  void limitMemory(GenericContainer<?> container, long bytes);

  /**
   * Set the cache eviction policy.
   *
   * <p>Common eviction policies (Redis):
   * <ul>
   *   <li>{@code noeviction} — return error when limit reached (default)</li>
   *   <li>{@code allkeys-lru} — evict least-recently used among all keys</li>
   *   <li>{@code volatile-lru} — evict LRU among keys with TTL only</li>
   *   <li>{@code allkeys-lfu} — evict least-frequently used among all keys</li>
   *   <li>{@code allkeys-random} — evict random keys</li>
   * </ul>
   * <p>Policy identifiers are backend-specific — consult your cache backend documentation.
   *
   * @param container target container
   * @param policy    backend-specific eviction policy identifier
   * @throws IllegalArgumentException if policy is null or blank
   */
  void setEvictionPolicy(GenericContainer<?> container, String policy);

  /**
   * Kill all clients connected to the cache backend.
   *
   * <p>Simulates a sudden cache restart — all active connections are dropped. Applications
   * relying on persistent connection pools observe reconnection behaviour.
   *
   * @param container target container
   */
  void disconnectClients(GenericContainer<?> container);

  /**
   * Flush all data from all databases/regions.
   *
   * <p>⚠️ Destructive — wipes the entire cache. Intended for test setup/teardown,
   * not chaos injection in running assertions.
   *
   * @param container target container
   */
  void flushAll(GenericContainer<?> container);

  // ==================== Lifecycle ====================

  /**
   * ✅ Targeted reset — removes the cache proxy and its iptables rule.
   *
   * <p>Surgical cleanup: only the named cache proxy is deleted from Toxiproxy.
   * The Toxiproxy process and all other proxies on the container stay alive.
   * Use this in {@code @AfterEach}.
   *
   * <p>No-op if the container is not running.
   *
   * <p>The proxy name removed is determined by the implementation's configuration
   * (e.g., {@code "redis_cache"} for {@code RedisCacheChaosProvider}).
   *
   * @param container target container
   */
  @Override
  void reset(GenericContainer<?> container);

  // ==================== Deprecated Legacy API ====================

  /**
   * @deprecated Use {@link #injectConnectionFailures(GenericContainer, double)} instead.
   *     This method drops TCP connections — not protocol-level cache misses.
   *     The name was misleading; the new method name reflects the actual behaviour.
   * @param container  target container
   * @param keyPattern ignored — TCP-level fault has no key awareness
   * @param rate       drop probability in [0.0, 1.0]
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void injectMisses(
      final GenericContainer<?> container, final String keyPattern, final double rate) {
    injectConnectionFailures(container, rate);
  }
}
