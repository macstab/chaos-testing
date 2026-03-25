/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Universal TCP proxy chaos for application-level fault injection.
 *
 * <p>Enables transparent fault injection into any TCP service (Redis, Postgres, MySQL, MongoDB,
 * HTTP, etc.) without modifying the service or application code. Uses Toxiproxy + iptables to
 * intercept and manipulate TCP traffic.
 *
 * <h2>Architecture: Transparent TCP Interception</h2>
 *
 * <pre>
 * Application connects to:     container-hostname:6379 (thinks it's Redis)
 *                                       ↓
 * iptables PREROUTING redirects to:    localhost:16379 (Toxiproxy proxy)
 *                                       ↓
 * Toxiproxy applies chaos, forwards to: localhost:6379 (real Redis)
 * </pre>
 *
 * <p><strong>Key principle:</strong> Application code unchanged, chaos injected transparently at
 * network layer.
 *
 * <h2>Complete Example: Redis Latency Testing</h2>
 *
 * <pre>{@code
 * import static org.assertj.core.api.Assertions.*;
 * import static org.junit.jupiter.api.Assertions.*;
 *
 * @Testcontainers
 * class RedisLatencyTest {
 *
 *   @Container
 *   GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
 *       .withExposedPorts(6379);
 *
 *   ProxyChaos chaos = new ProxyChaosProvider();
 *
 *   @Test
 *   @DisplayName("Application should handle slow Redis gracefully")
 *   void shouldHandleSlowRedis() {
 *     // Setup: Create transparent proxy for Redis
 *     String hostname = chaos.createProxy(redis, "redis", 6379, 16379);
 *
 *     // CRITICAL: Connect via hostname (NOT localhost!)
 *     Jedis client = new Jedis(hostname, 6379);
 *
 *     // Baseline: Verify connection works
 *     client.set("key", "value");
 *     assertThat(client.get("key")).isEqualTo("value");
 *
 *     // Inject chaos: Add 500ms latency to all Redis operations
 *     chaos.addLatency(redis, "redis", Duration.ofMillis(500));
 *
 *     // Verify: Application handles latency (slow but functional)
 *     long start = System.currentTimeMillis();
 *     String result = client.get("key");
 *     long duration = System.currentTimeMillis() - start;
 *
 *     assertThat(result).isEqualTo("value");
 *     assertThat(duration).isGreaterThanOrEqualTo(500); // 500ms latency applied
 *
 *     // Cleanup
 *     chaos.reset(redis);
 *     client.close();
 *   }
 *
 *   @Test
 *   @DisplayName("Application should handle Redis timeouts with circuit breaker")
 *   void shouldHandleRedisTimeouts() {
 *     String hostname = chaos.createProxy(redis, "redis", 6379, 16379);
 *     Jedis client = new Jedis(hostname, 6379);
 *
 *     // Inject chaos: 30% of connections timeout instantly
 *     chaos.addTimeout(redis, "redis", Duration.ZERO, 0.3);
 *
 *     // Verify: Application retries and eventually succeeds
 *     int successes = 0;
 *     int timeouts = 0;
 *
 *     for (int i = 0; i < 100; i++) {
 *       try {
 *         client.set("key" + i, "value" + i);
 *         successes++;
 *       } catch (JedisConnectionException e) {
 *         timeouts++;
 *       }
 *     }
 *
 *     assertThat(timeouts).isBetween(20, 40); // ~30% timeout rate
 *     assertThat(successes).isGreaterThan(60); // Most succeed via retry
 *
 *     chaos.reset(redis);
 *     client.close();
 *   }
 * }
 * }</pre>
 *
 * <h2>Supported Chaos Types</h2>
 *
 * <table border="1">
 *   <caption>Chaos Operations</caption>
 *   <tr>
 *     <th>Method</th>
 *     <th>Simulates</th>
 *     <th>Real-World Scenario</th>
 *   </tr>
 *   <tr>
 *     <td>{@link #addLatency}</td>
 *     <td>Network latency</td>
 *     <td>Slow networks, geo-distributed services</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #addTimeout}</td>
 *     <td>Connection hangs/timeouts</td>
 *     <td>Service outages, network partitions</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #limitBandwidth}</td>
 *     <td>Bandwidth throttling</td>
 *     <td>Slow connections, large response handling</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #slowClose}</td>
 *     <td>Connection close delays</td>
 *     <td>Resource leaks, connection pool exhaustion</td>
 *   </tr>
 * </table>
 *
 * <h2>CRITICAL: Hostname vs Localhost</h2>
 *
 * <p><strong>✅ CORRECT:</strong> Connect via hostname returned by createProxy()
 *
 * <pre>{@code
 * String hostname = chaos.createProxy(redis, "redis", 6379, 16379);
 * Jedis client = new Jedis(hostname, 6379);  // ✅ Traffic goes through proxy
 * }</pre>
 *
 * <p><strong>❌ WRONG:</strong> Connect to localhost
 *
 * <pre>{@code
 * chaos.createProxy(redis, "redis", 6379, 16379);
 * Jedis client = new Jedis("localhost", 6379);  // ❌ Bypasses proxy!
 * }</pre>
 *
 * <p><strong>Why?</strong> iptables rules only redirect traffic <em>from outside the
 * container</em>. Connections from inside bypass iptables.
 *
 * <h2>Port Selection Guidelines</h2>
 *
 * <ul>
 *   <li><strong>servicePort:</strong> Real service port (6379 for Redis, 5432 for Postgres)
 *   <li><strong>proxyPort:</strong> High port for Toxiproxy (16379, 15432, etc.)
 *   <li><strong>Convention:</strong> proxyPort = servicePort + 10000
 * </ul>
 *
 * <table border="1">
 *   <caption>Common Services</caption>
 *   <tr>
 *     <th>Service</th>
 *     <th>servicePort</th>
 *     <th>proxyPort</th>
 *   </tr>
 *   <tr>
 *     <td>Redis</td>
 *     <td>6379</td>
 *     <td>16379</td>
 *   </tr>
 *   <tr>
 *     <td>Postgres</td>
 *     <td>5432</td>
 *     <td>15432</td>
 *   </tr>
 *   <tr>
 *     <td>MySQL</td>
 *     <td>3306</td>
 *     <td>13306</td>
 *   </tr>
 *   <tr>
 *     <td>MongoDB</td>
 *     <td>27017</td>
 *     <td>37017</td>
 *   </tr>
 *   <tr>
 *     <td>HTTP</td>
 *     <td>8080</td>
 *     <td>18080</td>
 *   </tr>
 * </table>
 *
 * <h2>Testing Patterns</h2>
 *
 * <h3>Pattern 1: Baseline → Chaos → Verify Recovery</h3>
 *
 * <pre>{@code
 * @Test
 * void testChaosRecovery() {
 *   // 1. Baseline: Verify system works
 *   assertThat(service.healthCheck()).isTrue();
 *
 *   // 2. Inject chaos
 *   chaos.addLatency(container, "db", Duration.ofSeconds(2));
 *
 *   // 3. Verify degraded but functional
 *   assertThat(service.healthCheck()).isTrue();  // Slow but works
 *
 *   // 4. Remove chaos
 *   chaos.reset(container);
 *
 *   // 5. Verify full recovery
 *   assertThat(service.healthCheck()).isTrue();  // Fast again
 * }
 * }</pre>
 *
 * <h3>Pattern 2: Probabilistic Chaos (Flaky Networks)</h3>
 *
 * <pre>{@code
 * @Test
 * void testFlakyNetwork() {
 *   chaos.addTimeout(redis, "redis", Duration.ZERO, 0.2);  // 20% timeout rate
 *
 *   // Application must retry and succeed eventually
 *   RetryTemplate retry = new RetryTemplate();
 *   retry.setRetryPolicy(new SimpleRetryPolicy(5));
 *
 *   String result = retry.execute(ctx -> client.get("key"));
 *   assertThat(result).isNotNull();  // Succeeds via retry
 * }
 * }</pre>
 *
 * <h3>Pattern 3: Chaos + Observability</h3>
 *
 * <pre>{@code
 * @Test
 * void testMetricsUnderChaos() {
 *   chaos.addLatency(postgres, "db", Duration.ofMillis(500));
 *
 *   service.performQuery();
 *
 *   // Verify metrics captured latency
 *   assertThat(metrics.getQueryLatency()).isGreaterThan(500);
 *   assertThat(metrics.getSlowQueryCount()).isGreaterThan(0);
 * }
 * }</pre>
 *
 * <h2>Common Pitfalls</h2>
 *
 * <ul>
 *   <li>❌ Connecting to localhost instead of hostname (bypasses proxy)
 *   <li>❌ Forgetting to call {@link #reset} in @AfterEach (leaks Toxiproxy instances)
 *   <li>❌ Using same port for service and proxy (port conflict)
 *   <li>❌ Not handling timeouts in application (test fails instead of validating retry)
 *   <li>❌ Adding chaos before createProxy() (proxy must exist first)
 * </ul>
 *
 * <h2>Troubleshooting</h2>
 *
 * <p><strong>Problem:</strong> Chaos not applied, connections work normally
 *
 * <p><strong>Solution:</strong> Verify client connects to hostname (not localhost)
 *
 * <pre>{@code
 * // Debug: Print actual connection
 * String hostname = chaos.createProxy(redis, "redis", 6379, 16379);
 * System.out.println("Connect to: " + hostname + ":6379");  // Should NOT be localhost
 * }</pre>
 *
 * <p><strong>Problem:</strong> Connection refused after createProxy()
 *
 * <p><strong>Solution:</strong> Check port numbers, ensure no conflicts
 *
 * <pre>{@code
 * // Verify ports
 * chaos.createProxy(redis, "redis", 6379, 16379);
 * // Client connects to hostname:6379 (NOT 16379!)
 * }</pre>
 *
 * <p><strong>Problem:</strong> Tests hang forever
 *
 * <p><strong>Solution:</strong> Set client timeouts
 *
 * <pre>{@code
 * JedisPoolConfig config = new JedisPoolConfig();
 * config.setMaxWaitMillis(5000);  // 5s timeout
 * JedisPool pool = new JedisPool(config, hostname, 6379);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p><strong>ProxyChaos implementations are thread-safe.</strong> Multiple threads can call methods
 * concurrently on the same instance.
 *
 * <p><strong>Container isolation:</strong> Each container has independent Toxiproxy instance. Chaos
 * on container A does not affect container B.
 *
 * <h2>Performance</h2>
 *
 * <ul>
 *   <li><strong>Overhead:</strong> ~1-5ms latency per operation (Toxiproxy proxy hop)
 *   <li><strong>Throughput:</strong> ~100K ops/sec (limited by Toxiproxy, not iptables)
 *   <li><strong>Startup:</strong> ~100-200ms to install and start Toxiproxy
 * </ul>
 *
 * <h2>Implementation</h2>
 *
 * <p>Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-proxy:1.0.0")
 * }</pre>
 *
 * <p>Instantiate:
 *
 * <pre>{@code
 * ProxyChaos chaos = new ProxyChaosProvider();
 * }</pre>
 *
 * @see com.macstab.chaos.proxy.ProxyChaosProvider
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ProxyChaos {

  /**
   * Create a transparent TCP proxy for chaos injection.
   *
   * <p>Sets up iptables PREROUTING redirect + Toxiproxy proxy. All traffic to servicePort gets
   * transparently intercepted and routed through Toxiproxy on proxyPort.
   *
   * <h3>What This Does</h3>
   *
   * <ol>
   *   <li>Installs Toxiproxy binary (if not already installed)
   *   <li>Starts Toxiproxy server in background
   *   <li>Creates proxy: proxyPort → servicePort
   *   <li>Sets up iptables PREROUTING redirect: servicePort → proxyPort
   *   <li>Returns container hostname for client connections
   * </ol>
   *
   * <h3>Example: Redis Proxy</h3>
   *
   * <pre>{@code
   * // Setup
   * GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
   *     .withExposedPorts(6379);
   * redis.start();
   *
   * ProxyChaos chaos = new ProxyChaosProvider();
   *
   * // Create proxy for Redis
   * String hostname = chaos.createProxy(
   *     redis,
   *     "redis",    // Proxy name (for toxic management)
   *     6379,       // Real Redis port
   *     16379       // Toxiproxy intercept port
   * );
   *
   * // Connect via hostname (NOT localhost!)
   * Jedis client = new Jedis(hostname, 6379);
   *
   * // Now all Redis operations go through Toxiproxy
   * // Can inject latency, timeouts, etc.
   * client.set("key", "value");  // Intercepted by proxy
   * }</pre>
   *
   * <h3>Port Selection</h3>
   *
   * <ul>
   *   <li><strong>servicePort:</strong> The port your service actually listens on (e.g., Redis
   *       6379)
   *   <li><strong>proxyPort:</strong> High port for Toxiproxy (convention: servicePort + 10000)
   *   <li><strong>Client connects to:</strong> hostname:servicePort (e.g., "abc123:6379")
   * </ul>
   *
   * <h3>CRITICAL: Hostname vs Localhost</h3>
   *
   * <p><strong>✅ CORRECT:</strong>
   *
   * <pre>{@code
   * String hostname = chaos.createProxy(redis, "redis", 6379, 16379);
   * Jedis client = new Jedis(hostname, 6379);  // ✅ Goes through proxy
   * }</pre>
   *
   * <p><strong>❌ WRONG:</strong>
   *
   * <pre>{@code
   * chaos.createProxy(redis, "redis", 6379, 16379);
   * Jedis client = new Jedis("localhost", 6379);  // ❌ Bypasses proxy!
   * }</pre>
   *
   * <h3>Idempotency</h3>
   *
   * <p>Calling createProxy() multiple times with the same proxyName is idempotent. Existing proxy
   * is reused.
   *
   * <h3>Cleanup</h3>
   *
   * <p>Always call {@link #reset} to stop Toxiproxy and remove iptables rules:
   *
   * <pre>{@code
   * @AfterEach
   * void cleanup() {
   *   chaos.reset(redis);
   * }
   * }</pre>
   *
   * @param container container running the TCP service
   * @param proxyName unique proxy identifier (used for toxic management, e.g., "redis", "postgres")
   * @param servicePort real service port (6379 for Redis, 5432 for Postgres, 3306 for MySQL)
   * @param proxyPort Toxiproxy listen port (convention: servicePort + 10000)
   * @return container hostname (use this for client connections, NOT "localhost")
   * @throws IllegalArgumentException if ports are invalid (must be 1-65535)
   * @throws IllegalStateException if container is not running
   * @throws com.macstab.chaos.core.exception.ChaosOperationFailedException if proxy creation fails
   */
  String createProxy(
      GenericContainer<?> container, String proxyName, int servicePort, int proxyPort);

  /**
   * Add fixed latency to all TCP traffic through the proxy.
   *
   * <p>Simulates slow networks, geo-distributed services, or network congestion.
   *
   * <h3>Example: Test Application Handles Slow Redis</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldHandleSlowRedis() {
   *   String hostname = chaos.createProxy(redis, "redis", 6379, 16379);
   *   Jedis client = new Jedis(hostname, 6379);
   *
   *   // Add 500ms latency to all Redis operations
   *   chaos.addLatency(redis, "redis", Duration.ofMillis(500));
   *
   *   long start = System.currentTimeMillis();
   *   client.get("key");
   *   long duration = System.currentTimeMillis() - start;
   *
   *   assertThat(duration).isGreaterThanOrEqualTo(500);  // 500ms+ latency
   *
   *   chaos.reset(redis);
   * }
   * }</pre>
   *
   * <h3>Use Cases</h3>
   *
   * <ul>
   *   <li>Slow database queries (500-2000ms)
   *   <li>Geo-distributed services (100-300ms cross-region)
   *   <li>Mobile networks (50-200ms 4G/5G)
   *   <li>Timeout testing (add latency &gt; timeout threshold)
   * </ul>
   *
   * <h3>Idempotency</h3>
   *
   * <p>Calling multiple times replaces the previous latency value.
   *
   * @param container target container
   * @param proxyName proxy identifier (from {@link #createProxy})
   * @param latency latency to add (e.g., Duration.ofMillis(500) for 500ms)
   * @throws IllegalArgumentException if latency is negative
   * @throws IllegalStateException if proxy does not exist (call {@link #createProxy} first)
   */
  void addLatency(GenericContainer<?> container, String proxyName, Duration latency);

  /**
   * Add connection timeouts with probability (simulates intermittent service unavailability).
   *
   * <p>Randomly closes connections to simulate flaky networks, service outages, or network
   * partitions.
   *
   * <h3>Example: Test Circuit Breaker on 30% Timeout Rate</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldUseCircuitBreakerOnTimeouts() {
   *   String hostname = chaos.createProxy(redis, "redis", 6379, 16379);
   *   Jedis client = new Jedis(hostname, 6379);
   *
   *   // 30% of connections timeout instantly
   *   chaos.addTimeout(redis, "redis", Duration.ZERO, 0.3);
   *
   *   int timeouts = 0;
   *   for (int i = 0; i < 100; i++) {
   *     try {
   *       client.get("key" + i);
   *     } catch (JedisConnectionException e) {
   *       timeouts++;
   *     }
   *   }
   *
   *   assertThat(timeouts).isBetween(20, 40);  // ~30% timeout rate
   *
   *   chaos.reset(redis);
   * }
   * }</pre>
   *
   * <h3>Use Cases</h3>
   *
   * <ul>
   *   <li>Flaky networks (probability = 0.1-0.3 for 10-30% failure rate)
   *   <li>Intermittent outages (probability = 0.5 for 50% failure)
   *   <li>Circuit breaker testing (verify retry/fallback logic)
   *   <li>Complete outage (probability = 1.0 for 100% failure)
   * </ul>
   *
   * <h3>Timeout Values</h3>
   *
   * <ul>
   *   <li><strong>Duration.ZERO:</strong> Instant close (connection refused)
   *   <li><strong>Duration.ofSeconds(10):</strong> Connection hangs for 10s then closes
   * </ul>
   *
   * @param container target container
   * @param proxyName proxy identifier
   * @param timeout timeout duration (Duration.ZERO = instant close, &gt;0 = hang then close)
   * @param probability failure probability (0.0-1.0, e.g., 0.3 = 30% of connections fail)
   * @throws IllegalArgumentException if probability is not in [0.0, 1.0] or timeout is negative
   */
  void addTimeout(
      GenericContainer<?> container, String proxyName, Duration timeout, double probability);

  /**
   * Limit TCP bandwidth through the proxy (rate limiting).
   *
   * <p>Simulates slow connections, large response handling, or bandwidth constraints.
   *
   * <h3>Example: Test Large Response Streaming</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldStreamLargeResponsesUnderBandwidthLimit() {
   *   String hostname = chaos.createProxy(http, "api", 8080, 18080);
   *   RestTemplate client = new RestTemplate(hostname + ":8080");
   *
   *   // Limit to 10 KB/s (slow connection)
   *   chaos.limitBandwidth(http, "api", 10);
   *
   *   long start = System.currentTimeMillis();
   *   byte[] response = client.getForObject("/large-file", byte[].class);  // 100KB response
   *   long duration = System.currentTimeMillis() - start;
   *
   *   assertThat(response).hasSize(100 * 1024);
   *   assertThat(duration).isGreaterThan(10_000);  // 100KB at 10KB/s = 10+ seconds
   *
   *   chaos.reset(http);
   * }
   * }</pre>
   *
   * <h3>Use Cases</h3>
   *
   * <ul>
   *   <li>Slow connections: 10-50 KB/s
   *   <li>Mobile 3G: 50-100 KB/s
   *   <li>Large response handling: Test streaming vs buffering
   *   <li>Timeout testing: Low bandwidth + large response = timeout
   * </ul>
   *
   * @param container target container
   * @param proxyName proxy identifier
   * @param rateKBps bandwidth limit in kilobytes per second (e.g., 10 = 10 KB/s)
   * @throws IllegalArgumentException if rateKBps is &lt;= 0
   */
  void limitBandwidth(GenericContainer<?> container, String proxyName, long rateKBps);

  /**
   * Delay connection close (connection hangs during shutdown).
   *
   * <p>Simulates slow connection cleanup, tests resource leak handling and connection pool
   * behavior.
   *
   * <h3>Example: Test Connection Pool Doesn't Leak</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldNotLeakConnectionsOnSlowClose() {
   *   String hostname = chaos.createProxy(postgres, "db", 5432, 15432);
   *   HikariDataSource pool = new HikariDataSource();
   *   pool.setJdbcUrl("jdbc:postgresql://" + hostname + ":5432/db");
   *   pool.setMaximumPoolSize(10);
   *
   *   // Connections take 5 seconds to close
   *   chaos.slowClose(postgres, "db", Duration.ofSeconds(5));
   *
   *   // Open and close 20 connections
   *   for (int i = 0; i < 20; i++) {
   *     try (Connection conn = pool.getConnection()) {
   *       conn.createStatement().execute("SELECT 1");
   *     }
   *   }
   *
   *   // Pool should not be exhausted (connections eventually close)
   *   assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isLessThan(10);
   *
   *   chaos.reset(postgres);
   *   pool.close();
   * }
   * }</pre>
   *
   * <h3>Use Cases</h3>
   *
   * <ul>
   *   <li>Connection pool leak testing
   *   <li>Resource cleanup validation
   *   <li>Graceful shutdown testing
   * </ul>
   *
   * @param container target container
   * @param proxyName proxy identifier
   * @param delay delay before connection closes (e.g., Duration.ofSeconds(5))
   * @throws IllegalArgumentException if delay is negative
   */
  void slowClose(GenericContainer<?> container, String proxyName, Duration delay);

  /**
   * Remove all toxics, stop Toxiproxy, and cleanup iptables rules.
   *
   * <p><strong>CRITICAL:</strong> Always call in @AfterEach to prevent resource leaks.
   *
   * <h3>Example: Proper Cleanup</h3>
   *
   * <pre>{@code
   * @Testcontainers
   * class MyTest {
   *
   *   @Container
   *   GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
   *       .withExposedPorts(6379);
   *
   *   ProxyChaos chaos = new ProxyChaosProvider();
   *
   *   @AfterEach
   *   void cleanup() {
   *     chaos.reset(redis);  // Stop Toxiproxy, remove iptables rules
   *   }
   *
   *   @Test
   *   void test() {
   *     chaos.createProxy(redis, "redis", 6379, 16379);
   *     chaos.addLatency(redis, "redis", Duration.ofMillis(100));
   *     // Test logic...
   *   }
   * }
   * }</pre>
   *
   * <h3>What This Does</h3>
   *
   * <ol>
   *   <li>Removes all toxics from all proxies
   *   <li>Stops Toxiproxy server process
   *   <li>Removes iptables PREROUTING rules
   *   <li>Cleans up internal state
   * </ol>
   *
   * <h3>Idempotency</h3>
   *
   * <p>Safe to call multiple times. No-op if already reset.
   *
   * @param container target container
   */
  void reset(GenericContainer<?> container);

  /**
   * Check if proxy chaos is supported on this container's platform.
   *
   * <p>Proxy chaos requires Linux with iptables support. Returns false on:
   *
   * <ul>
   *   <li>Windows containers (no iptables)
   *   <li>macOS containers (no iptables)
   *   <li>Alpine containers without iptables package
   *   <li>Containers without NET_ADMIN capability
   * </ul>
   *
   * <h3>Example: Conditional Testing</h3>
   *
   * <pre>{@code
   * @Test
   * void testProxyChaos() {
   *   ProxyChaos chaos = new ProxyChaosProvider();
   *
   *   Assumptions.assumeTrue(chaos.isSupported(), "Proxy chaos requires iptables");
   *
   *   // Test logic...
   * }
   * }</pre>
   *
   * @return true if proxy chaos is supported on current platform, false otherwise
   */
  boolean isSupported();
}
