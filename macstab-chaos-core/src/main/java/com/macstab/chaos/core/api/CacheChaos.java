/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Cache chaos injection for Redis containers.
 *
 * <p>Injects TCP-level faults via Toxiproxy and Redis-level faults via redis-cli. No application
 * code changes required — all chaos is applied transparently at the network and data layer.
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>⚠️ CRITICAL: Lifecycle — deleteProxy vs reset</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p><strong>One Toxiproxy process handles ALL proxies inside a container.</strong> If another
 * module (database chaos, proxy chaos) also creates proxies on the same container, nuclear reset
 * destroys their proxies too.
 *
 * <ul>
 *   <li>⛔ {@link #reset} is <strong>nuclear</strong>: kills Toxiproxy, removes ALL iptables
 *       rules. Use only in {@code @AfterAll} when the container is done.</li>
 *   <li>✅ {@link #reset} here is <strong>targeted</strong> by default: {@code CacheChaosProvider}
 *       calls {@code proxy.deleteProxy(container, config.proxyName())} — only the Redis proxy
 *       is removed. The Toxiproxy process stays alive.</li>
 * </ul>
 *
 * <pre>{@code
 * // ✅ CORRECT — per-test: remove only cache proxy
 * @AfterEach
 * void cleanup() {
 *     cacheChaos.reset(container);   // removes "redis_cache" proxy only
 * }
 *
 * // ✅ CORRECT — container is done, nuke everything
 * @AfterAll
 * static void teardown() {
 *     proxyChaos.reset(container);   // kills Toxiproxy + all iptables rules
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
 * <h2>Quick Start</h2>
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
 *   CacheChaos chaos = new CacheChaosProvider();
 *
 *   @AfterEach
 *   void cleanup() {
 *       chaos.reset(redis);  // removes cache proxy (surgical, not nuclear)
 *   }
 *
 *   @Test
 *   void shouldHandleSlowCache() {
 *       chaos.slowResponse(redis, Duration.ofMillis(200));
 *       // ... test application behaviour under latency
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
 * <p><strong>TCP-level faults</strong> (via Toxiproxy — transparent to Redis):
 * <ul>
 *   <li>{@link #slowResponse} — add fixed latency to all operations</li>
 *   <li>{@link #injectConnectionFailures} — drop TCP connections probabilistically</li>
 *   <li>{@link #limitThroughput} — cap transfer rate (simulates slow network)</li>
 *   <li>{@link #truncateResponses} — close connection mid-response (broken RESP framing)</li>
 *   <li>{@link #removeFault} — remove a named fault</li>
 *   <li>{@link #removeAllFaults} — remove all faults, proxy stays active</li>
 * </ul>
 *
 * <p><strong>Redis-level faults</strong> (via redis-cli — bypass proxy):
 * <ul>
 *   <li>{@link #forceEviction} — delete percentage of keys (simulate LRU pressure)</li>
 *   <li>{@link #limitMemory} — set maxmemory limit (trigger real eviction)</li>
 *   <li>{@link #setEvictionPolicy} — change eviction policy (allkeys-lru, volatile-lru, etc.)</li>
 *   <li>{@link #disconnectClients} — kill all connected clients</li>
 *   <li>{@link #flushAll} — wipe entire cache (use in teardown)</li>
 * </ul>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Fault Naming Convention</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p>TCP faults are registered in Toxiproxy with fixed names matching their method name:
 * {@code "latency"}, {@code "timeout"}, {@code "bandwidth"}, {@code "limit_data"}.
 * Use these names with {@link #removeFault}.
 *
 * <pre>{@code
 * chaos.slowResponse(redis, Duration.ofMillis(200));   // creates "latency" toxic
 * chaos.removeFault(redis, "latency");                 // removes it
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
 * <pre>{@code
 * CacheChaos chaos = new CacheChaosProvider();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface CacheChaos extends ChaosProvider {

  // ==================== TCP-Level Faults (via Toxiproxy) ====================

  /**
   * Add fixed latency to all Redis operations.
   *
   * <p>Simulates slow networks, geo-distributed Redis, or a loaded Redis instance.
   * Creates a {@code "latency"} toxic in Toxiproxy.
   *
   * <pre>{@code
   * chaos.slowResponse(redis, Duration.ofMillis(200));   // 200ms on every op
   * }</pre>
   *
   * @param container target container
   * @param delay latency to add (must not be null or negative)
   */
  void slowResponse(GenericContainer<?> container, Duration delay);

  /**
   * Drop TCP connections to Redis probabilistically.
   *
   * <p>Simulates flaky networks, intermittent Redis outages, or network partitions.
   * Creates a {@code "timeout"} toxic (instant close) in Toxiproxy.
   *
   * <p><strong>Note:</strong> This is a TCP-level connection drop — not a Redis-level cache miss.
   * Applications see {@code JedisConnectionException} or similar, not a null return value.
   *
   * <pre>{@code
   * chaos.injectConnectionFailures(redis, 0.3);  // 30% of connections dropped instantly
   * }</pre>
   *
   * @param container target container
   * @param rate drop probability in [0.0, 1.0] (e.g., 0.3 = 30% of connections dropped)
   * @throws IllegalArgumentException if rate is not in [0.0, 1.0]
   */
  void injectConnectionFailures(GenericContainer<?> container, double rate);

  /**
   * Cap TCP transfer rate through the proxy.
   *
   * <p>Simulates a slow connection, large-value operations over a bandwidth-constrained link,
   * or Redis receiving bulk MSET/pipeline payloads on a throttled link.
   * Creates a {@code "bandwidth"} toxic in Toxiproxy.
   *
   * <pre>{@code
   * chaos.limitThroughput(redis, 10);  // 10 KB/s — noticeably slow for large values
   * }</pre>
   *
   * @param container target container
   * @param rateKBps bandwidth limit in kilobytes per second (must be &gt; 0)
   * @throws IllegalArgumentException if rateKBps is not positive
   */
  void limitThroughput(GenericContainer<?> container, long rateKBps);

  /**
   * Close the TCP connection after a fixed number of bytes (truncate mid-response).
   *
   * <p>Injects broken RESP framing — the client receives a partial response and must handle
   * the truncation gracefully. Creates a {@code "limit_data"} toxic in Toxiproxy.
   *
   * <pre>{@code
   * chaos.truncateResponses(redis, 1024);  // close after 1 KB — truncates most GET responses
   * chaos.truncateResponses(redis, 0);     // close immediately on first data
   * }</pre>
   *
   * @param container target container
   * @param bytes byte threshold at which the connection is closed (0 = instant close on first data)
   * @throws IllegalArgumentException if bytes is negative
   */
  void truncateResponses(GenericContainer<?> container, long bytes);

  /**
   * Remove a named TCP fault from the proxy.
   *
   * <p>No-op if the fault does not exist. Fault names match the method that created them:
   * {@code "latency"}, {@code "timeout"}, {@code "bandwidth"}, {@code "limit_data"}.
   *
   * <pre>{@code
   * chaos.removeFault(redis, "latency");   // remove latency toxic only
   * }</pre>
   *
   * @param container target container
   * @param faultName toxic name to remove
   */
  void removeFault(GenericContainer<?> container, String faultName);

  /**
   * Remove all TCP faults from the proxy.
   *
   * <p>Restores normal throughput and latency without removing the proxy itself.
   * The proxy stays active — use {@link #reset} to delete the proxy.
   *
   * @param container target container
   */
  void removeAllFaults(GenericContainer<?> container);

  // ==================== Redis-Level Faults (via redis-cli) ====================

  /**
   * Force-evict a percentage of all keys by scanning and deleting.
   *
   * <p>Simulates LRU eviction pressure — the application's cache hit ratio drops immediately.
   * Operates directly on Redis (bypasses the proxy), so it works regardless of active TCP faults.
   *
   * <pre>{@code
   * chaos.forceEviction(redis, 50);   // delete ~50% of keys
   * chaos.forceEviction(redis, 100);  // delete all keys (equivalent to FLUSHALL by %)
   * }</pre>
   *
   * @param container target container
   * @param percentage percentage of keys to evict (1–100)
   * @throws IllegalArgumentException if percentage is not in [1, 100]
   */
  void forceEviction(GenericContainer<?> container, int percentage);

  /**
   * Set Redis {@code maxmemory} to trigger real LRU/LFU eviction.
   *
   * <p>Redis evicts keys automatically once the limit is reached and the active eviction policy
   * allows it. Combine with {@link #setEvictionPolicy} for full control.
   *
   * <pre>{@code
   * chaos.limitMemory(redis, 64 * 1024 * 1024L);  // 64 MB limit
   * chaos.setEvictionPolicy(redis, "allkeys-lru"); // evict least-recently used
   * }</pre>
   *
   * @param container target container
   * @param bytes memory limit in bytes (0 = no limit, restores default)
   * @throws IllegalArgumentException if bytes is negative
   */
  void limitMemory(GenericContainer<?> container, long bytes);

  /**
   * Set Redis eviction policy via {@code CONFIG SET maxmemory-policy}.
   *
   * <p>Standard Redis policies:
   * <ul>
   *   <li>{@code noeviction} — return error when memory limit reached (default)</li>
   *   <li>{@code allkeys-lru} — evict least-recently used among all keys</li>
   *   <li>{@code volatile-lru} — evict LRU among keys with TTL only</li>
   *   <li>{@code allkeys-lfu} — evict least-frequently used among all keys</li>
   *   <li>{@code allkeys-random} — evict random keys</li>
   * </ul>
   *
   * @param container target container
   * @param policy eviction policy string (see Redis docs for full list)
   * @throws IllegalArgumentException if policy is null or blank
   */
  void setEvictionPolicy(GenericContainer<?> container, String policy);

  /**
   * Kill all clients connected to Redis via {@code CLIENT KILL}.
   *
   * <p>Simulates a sudden Redis restart — all active connections are dropped. Applications
   * relying on persistent connection pools will observe reconnection behaviour.
   *
   * @param container target container
   */
  void disconnectClients(GenericContainer<?> container);

  /**
   * Flush all keys from all databases via {@code FLUSHALL}.
   *
   * <p>⚠️ Destructive — wipes the entire cache. Intended for test setup/teardown,
   * not chaos injection in running assertions.
   *
   * @param container target container
   */
  void flushAll(GenericContainer<?> container);

  // ==================== Lifecycle ====================

  /**
   * ✅ Targeted reset — removes the Redis proxy and its iptables rule.
   *
   * <p>Surgical cleanup: only the named Redis proxy is deleted from Toxiproxy.
   * The Toxiproxy process and all other proxies on the container stay alive.
   * Use this in {@code @AfterEach}.
   *
   * <p>No-op if the container is not running.
   *
   * @param container target container
   */
  @Override
  void reset(GenericContainer<?> container);

  // ==================== Deprecated Legacy API ====================

  /**
   * Inject cache misses (TCP connection drop, not a real RESP-level miss).
   *
   * @deprecated Use {@link #injectConnectionFailures(GenericContainer, double)} instead.
   *     This method drops TCP connections — not Redis protocol cache misses.
   *     The name was misleading; the new method name reflects the actual behaviour.
   * @param container target container
   * @param keyPattern ignored (TCP-level fault has no key awareness)
   * @param rate drop probability in [0.0, 1.0]
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void injectMisses(
      final GenericContainer<?> container, final String keyPattern, final double rate) {
    injectConnectionFailures(container, rate);
  }
}
