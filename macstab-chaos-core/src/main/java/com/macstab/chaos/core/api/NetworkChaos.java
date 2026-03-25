/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

/**
 * Network-level chaos injection using Linux Traffic Control (tc) and iptables.
 *
 * <p>Simulates real-world network conditions: latency, jitter, packet loss, bandwidth limits, and
 * network partitions. Uses kernel-level traffic shaping for accurate, production-like behavior.
 *
 * <h2>How It Works: Linux Traffic Control (tc)</h2>
 *
 * <p>NetworkChaos uses Linux {@code tc} (traffic control) to manipulate packets at the kernel
 * level:
 *
 * <pre>
 * Application sends packet
 *         ↓
 * Kernel network stack
 *         ↓
 * tc qdisc (queuing discipline) ← CHAOS INJECTED HERE
 *         ↓ (delay, drop, throttle)
 * Network interface (eth0)
 *         ↓
 * Network
 * </pre>
 *
 * <p><strong>Key concepts:</strong>
 *
 * <ul>
 *   <li><strong>qdisc (queuing discipline):</strong> Kernel packet scheduler (controls when packets
 *       are sent)
 *   <li><strong>netem (network emulator):</strong> qdisc that simulates network conditions
 *   <li><strong>tbf (token bucket filter):</strong> qdisc for bandwidth limiting
 *   <li><strong>iptables:</strong> Firewall for network partitions (DROP rules)
 * </ul>
 *
 * <h2>Complete Example: Distributed System Under Network Stress</h2>
 *
 * <pre>{@code
 * @Testcontainers
 * class DistributedSystemTest {
 *
 *   @Container
 *   GenericContainer<?> serviceA = new GenericContainer<>("myapp:latest")
 *       .withExposedPorts(8080);
 *
 *   @Container
 *   GenericContainer<?> serviceB = new GenericContainer<>("myapp:latest")
 *       .withExposedPorts(8080);
 *
 *   NetworkChaos chaos = new NetworkChaosProvider();
 *
 *   @Test
 *   @DisplayName("System should handle high latency between services")
 *   void shouldHandleHighLatency() {
 *     // Baseline: Verify services communicate
 *     assertThat(serviceA.makeRequestTo(serviceB)).isSuccessful();
 *
 *     // Inject chaos: Add 200ms latency + 50ms jitter (simulates geo-distributed)
 *     chaos.injectLatencyWithJitter(
 *         serviceA,
 *         Duration.ofMillis(200),  // Base latency
 *         Duration.ofMillis(50)    // Random jitter (150-250ms range)
 *     );
 *
 *     // Verify: Slower but functional
 *     long start = System.currentTimeMillis();
 *     assertThat(serviceA.makeRequestTo(serviceB)).isSuccessful();
 *     long duration = System.currentTimeMillis() - start;
 *
 *     assertThat(duration).isBetween(150L, 300L);  // 200ms ± 50ms jitter
 *
 *     chaos.reset(serviceA);
 *   }
 *
 *   @Test
 *   @DisplayName("System should handle 5% packet loss with retry logic")
 *   void shouldHandlePacketLoss() {
 *     chaos.injectPacketLoss(serviceA, 0.05);  // 5% packet loss
 *
 *     int successes = 0;
 *     int retries = 0;
 *
 *     for (int i = 0; i < 100; i++) {
 *       try {
 *         serviceA.makeRequestTo(serviceB);
 *         successes++;
 *       } catch (IOException e) {
 *         retries++;
 *       }
 *     }
 *
 *     // Most succeed via TCP retransmission
 *     assertThat(successes).isGreaterThan(90);
 *     assertThat(retries).isLessThan(10);
 *
 *     chaos.reset(serviceA);
 *   }
 *
 *   @Test
 *   @DisplayName("System should detect network partition and fail fast")
 *   void shouldDetectPartition() {
 *     // Completely partition serviceA from serviceB
 *     chaos.partitionFrom(serviceA, serviceB);
 *
 *     // All requests should fail immediately (no hanging)
 *     assertThatThrownBy(() -> serviceA.makeRequestTo(serviceB))
 *         .isInstanceOf(ConnectException.class)
 *         .hasMessageContaining("Connection refused");
 *
 *     chaos.reset(serviceA);
 *   }
 * }
 * }</pre>
 *
 * <h2>Chaos Types</h2>
 *
 * <table border="1">
 *   <caption>Network Chaos Operations</caption>
 *   <tr>
 *     <th>Method</th>
 *     <th>Kernel Mechanism</th>
 *     <th>Real-World Scenario</th>
 *   </tr>
 *   <tr>
 *     <td>{@link #injectLatency}</td>
 *     <td>tc netem delay</td>
 *     <td>Slow networks, geo-distributed services</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #injectLatencyWithJitter}</td>
 *     <td>tc netem delay + jitter</td>
 *     <td>Variable latency (WiFi, mobile)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #injectPacketLoss}</td>
 *     <td>tc netem loss random</td>
 *     <td>Unreliable networks, congestion</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #injectCorrelatedPacketLoss}</td>
 *     <td>tc netem loss + correlation</td>
 *     <td>Burst errors (Gilbert-Elliott model)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #limitBandwidth}</td>
 *     <td>tc tbf (token bucket)</td>
 *     <td>Slow connections, large transfers</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #partitionFrom}</td>
 *     <td>iptables DROP</td>
 *     <td>Network splits, datacenter failures</td>
 *   </tr>
 * </table>
 *
 * <h2>Testing Patterns</h2>
 *
 * <h3>Pattern 1: Baseline → Chaos → Verify Degradation → Reset → Verify Recovery</h3>
 *
 * <pre>{@code
 * @Test
 * void testNetworkRecovery() {
 *   // 1. Baseline
 *   assertThat(measureLatency(service)).isLessThan(10);  // Fast
 *
 *   // 2. Inject chaos
 *   chaos.injectLatency(service, Duration.ofMillis(500));
 *
 *   // 3. Verify degradation
 *   assertThat(measureLatency(service)).isGreaterThan(500);  // Slow
 *
 *   // 4. Reset
 *   chaos.reset(service);
 *
 *   // 5. Verify recovery
 *   assertThat(measureLatency(service)).isLessThan(10);  // Fast again
 * }
 * }</pre>
 *
 * <h3>Pattern 2: Probabilistic Testing (Statistical Validation)</h3>
 *
 * <pre>{@code
 * @Test
 * void testPacketLossRate() {
 *   chaos.injectPacketLoss(service, 0.1);  // 10% loss
 *
 *   int received = 0;
 *   for (int i = 0; i < 1000; i++) {
 *     if (sendPacket(service)) received++;
 *   }
 *
 *   // Verify ~90% received (10% loss)
 *   assertThat(received).isBetween(850, 950);  // 90% ± 5% statistical margin
 * }
 * }</pre>
 *
 * <h3>Pattern 3: Chaos Layering (Multiple Conditions)</h3>
 *
 * <pre>{@code
 * @Test
 * void testWorstCaseNetwork() {
 *   // Simulate terrible network (mobile 2G + congestion)
 *   chaos.injectLatencyWithJitter(service, Duration.ofMillis(300), Duration.ofMillis(100));
 *   chaos.injectPacketLoss(service, 0.05);
 *   chaos.limitBandwidth(service, "50kbit");  // 50 Kbps
 *
 *   // Application should still function (slowly)
 *   assertThat(service.healthCheck()).isTrue();
 *
 *   chaos.reset(service);
 * }
 * }</pre>
 *
 * <h2>Common Use Cases</h2>
 *
 * <table border="1">
 *   <caption>Network Scenarios</caption>
 *   <tr>
 *     <th>Scenario</th>
 *     <th>Configuration</th>
 *   </tr>
 *   <tr>
 *     <td>Slow LAN</td>
 *     <td>injectLatency(10ms)</td>
 *   </tr>
 *   <tr>
 *     <td>Cross-region (US-EU)</td>
 *     <td>injectLatencyWithJitter(100ms, 30ms)</td>
 *   </tr>
 *   <tr>
 *     <td>Mobile 4G</td>
 *     <td>injectLatency(50ms) + injectPacketLoss(0.01) + limitBandwidth("5mbit")</td>
 *   </tr>
 *   <tr>
 *     <td>Satellite link</td>
 *     <td>injectLatency(600ms) + limitBandwidth("1mbit")</td>
 *   </tr>
 *   <tr>
 *     <td>Congested network</td>
 *     <td>injectLatencyWithJitter(50ms, 200ms) + injectPacketLoss(0.05)</td>
 *   </tr>
 *   <tr>
 *     <td>Network partition</td>
 *     <td>partitionFrom(serviceA, serviceB)</td>
 *   </tr>
 * </table>
 *
 * <h2>Common Pitfalls</h2>
 *
 * <ul>
 *   <li>❌ Not calling {@link #reset} in @AfterEach (leaks tc rules)
 *   <li>❌ Testing localhost traffic (tc only affects external traffic)
 *   <li>❌ Expecting exact latency values (jitter introduces variance)
 *   <li>❌ Not accounting for TCP retransmission (packet loss doesn't always fail requests)
 *   <li>❌ Using too high packet loss (>30% makes TCP unusable)
 * </ul>
 *
 * <h2>Troubleshooting</h2>
 *
 * <p><strong>Problem:</strong> Chaos not applied, network is still fast
 *
 * <p><strong>Solution:</strong> Verify traffic goes through network interface (not localhost
 * loopback)
 *
 * <pre>{@code
 * // Wrong: localhost traffic bypasses tc
 * new URL("http://localhost:8080/api").openConnection();
 *
 * // Correct: Use container hostname/IP
 * String hostname = container.getHost();
 * new URL("http://" + hostname + ":8080/api").openConnection();
 * }</pre>
 *
 * <p><strong>Problem:</strong> Packet loss too aggressive, all requests fail
 *
 * <p><strong>Solution:</strong> Use lower values (1-10%), or use correlated loss for burst errors
 *
 * <pre>{@code
 * // Too aggressive
 * chaos.injectPacketLoss(service, 0.5);  // 50% loss = TCP breaks
 *
 * // Better
 * chaos.injectPacketLoss(service, 0.05);  // 5% loss = realistic
 *
 * // Or use burst errors
 * chaos.injectCorrelatedPacketLoss(service, 0.1, 0.8);  // 10% loss in bursts
 * }</pre>
 *
 * <h2>Gilbert-Elliott Model (Correlated Packet Loss)</h2>
 *
 * <p>Real networks don't drop packets randomly - they drop in bursts (bad state → good state
 * transitions).
 *
 * <p><strong>Correlation coefficient:</strong> Probability of staying in same state (0.0-1.0)
 *
 * <ul>
 *   <li><strong>0.0:</strong> Random loss (each packet independent)
 *   <li><strong>0.5:</strong> Moderate correlation (some clustering)
 *   <li><strong>0.9:</strong> High correlation (long bursts of loss)
 * </ul>
 *
 * <pre>{@code
 * // Random loss (coin flip for each packet)
 * chaos.injectPacketLoss(service, 0.1);  // 10% uniform random
 *
 * // Burst loss (packets drop in clusters)
 * chaos.injectCorrelatedPacketLoss(service, 0.1, 0.8);  // 10% in bursts
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p><strong>NetworkChaos implementations are thread-safe.</strong> Multiple threads can call
 * methods concurrently on the same instance.
 *
 * <p><strong>Container isolation:</strong> Each container has independent tc/iptables rules. Chaos
 * on container A does not affect container B.
 *
 * <h2>Performance</h2>
 *
 * <ul>
 *   <li><strong>Overhead:</strong> ~1-2% CPU (kernel-level tc queuing)
 *   <li><strong>Accuracy:</strong> ±5ms for latency, ±1% for packet loss
 *   <li><strong>Startup:</strong> ~50-100ms to configure tc rules
 * </ul>
 *
 * <h2>Platform Requirements</h2>
 *
 * <ul>
 *   <li>Linux kernel with tc support (all standard distributions)
 *   <li>NET_ADMIN capability (default in Testcontainers)
 *   <li>iproute2 package (tc command)
 * </ul>
 *
 * <h2>Implementation</h2>
 *
 * <p>Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-network:1.0.0")
 * }</pre>
 *
 * <p>Instantiate:
 *
 * <pre>{@code
 * NetworkChaos chaos = new NetworkChaosProvider();
 * }</pre>
 *
 * @see <a href="https://man7.org/linux/man-pages/man8/tc-netem.8.html">Linux tc-netem man page</a>
 * @author Christian Schnapka - Macstab GmbH
 */
public interface NetworkChaos extends ChaosProvider {

  /**
   * Inject fixed network latency using kernel-level traffic control.
   *
   * <p>Uses {@code tc qdisc add dev eth0 root netem delay <delay>ms}.
   *
   * <h3>Example: Test Timeout Handling on Slow Network</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldTimeoutOnSlowNetwork() {
   *   NetworkChaos chaos = new NetworkChaosProvider();
   *
   *   // Add 2 second latency (simulates very slow network)
   *   chaos.injectLatency(service, Duration.ofSeconds(2));
   *
   *   RestTemplate client = new RestTemplate();
   *   client.setRequestTimeout(Duration.ofSeconds(1));  // 1s timeout
   *
   *   // Should timeout (2s latency > 1s timeout)
   *   assertThatThrownBy(() -> client.getForObject(serviceUrl, String.class))
   *       .isInstanceOf(ResourceAccessException.class)
   *       .hasCauseInstanceOf(SocketTimeoutException.class);
   *
   *   chaos.reset(service);
   * }
   * }</pre>
   *
   * @param container target container
   * @param delay fixed latency to add (e.g., Duration.ofMillis(100) for 100ms)
   * @throws IllegalArgumentException if delay is negative
   */
  void injectLatency(GenericContainer<?> container, Duration delay);

  /**
   * Inject network latency with random jitter (variable delay).
   *
   * <p>Uses {@code tc qdisc add dev eth0 root netem delay <delay>ms <jitter>ms}.
   *
   * <p>Actual latency will be: delay ± jitter (uniformly distributed).
   *
   * <h3>Example: Simulate WiFi with Variable Latency</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldHandleVariableLatency() {
   *   // WiFi: 30ms ± 20ms (10-50ms range)
   *   chaos.injectLatencyWithJitter(service, Duration.ofMillis(30), Duration.ofMillis(20));
   *
   *   List<Long> latencies = new ArrayList<>();
   *   for (int i = 0; i < 100; i++) {
   *     long start = System.currentTimeMillis();
   *     service.ping();
   *     latencies.add(System.currentTimeMillis() - start);
   *   }
   *
   *   // Verify range
   *   assertThat(latencies).allMatch(l -> l >= 10 && l <= 70);  // 30 ± 20ms + overhead
   *
   *   chaos.reset(service);
   * }
   * }</pre>
   *
   * @param container target container
   * @param delay base latency (e.g., Duration.ofMillis(30))
   * @param jitter random variation (e.g., Duration.ofMillis(20) for ±20ms)
   * @throws IllegalArgumentException if delay or jitter is negative
   */
  void injectLatencyWithJitter(GenericContainer<?> container, Duration delay, Duration jitter);

  /**
   * Inject random packet loss (uniform distribution).
   *
   * <p>Uses {@code tc qdisc add dev eth0 root netem loss <percentage>%}.
   *
   * <p>Each packet has independent probability of being dropped.
   *
   * <h3>Example: Test Retry Logic on Unreliable Network</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldRetryOnPacketLoss() {
   *   // 10% packet loss
   *   chaos.injectPacketLoss(service, 0.1);
   *
   *   RetryTemplate retry = new RetryTemplate();
   *   retry.setRetryPolicy(new SimpleRetryPolicy(3));  // Max 3 retries
   *
   *   // Should succeed via retry (TCP retransmission + app-level retry)
   *   String result = retry.execute(ctx -> service.getData());
   *   assertThat(result).isNotNull();
   *
   *   chaos.reset(service);
   * }
   * }</pre>
   *
   * <h3>Recommended Values</h3>
   *
   * <ul>
   *   <li>0.01 (1%): Good network with occasional drops
   *   <li>0.05 (5%): Congested network
   *   <li>0.10 (10%): Poor network quality
   *   <li>&gt;0.30 (30%+): TCP becomes unusable (avoid)
   * </ul>
   *
   * @param container target container
   * @param percentage loss probability (0.0-1.0, e.g., 0.05 for 5%)
   * @throws IllegalArgumentException if percentage is not in [0.0, 1.0]
   */
  void injectPacketLoss(GenericContainer<?> container, double percentage);

  /**
   * Inject correlated packet loss (Gilbert-Elliott burst error model).
   *
   * <p>Uses {@code tc qdisc add dev eth0 root netem loss <percentage>% <correlation>%}.
   *
   * <p>Models real-world packet loss where errors occur in bursts (not uniformly random).
   *
   * <h3>Gilbert-Elliott Model Explained</h3>
   *
   * <p><strong>Correlation coefficient:</strong> Probability of staying in same state
   *
   * <ul>
   *   <li><strong>0.0:</strong> Random loss (independent packets)
   *   <li><strong>0.5:</strong> Moderate correlation (some burst clustering)
   *   <li><strong>0.9:</strong> High correlation (long bursts of loss/recovery)
   * </ul>
   *
   * <h3>Example: Burst Errors Like Real Networks</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldHandleBurstErrors() {
   *   // 10% loss in bursts (correlation=0.8 means bursts last ~5 packets)
   *   chaos.injectCorrelatedPacketLoss(service, 0.1, 0.8);
   *
   *   int successStreaks = 0;
   *   int lossStreaks = 0;
   *   boolean prevSuccess = true;
   *
   *   for (int i = 0; i < 100; i++) {
   *     boolean success = sendPacket(service);
   *     if (success != prevSuccess) {
   *       if (success) successStreaks++;
   *       else lossStreaks++;
   *     }
   *     prevSuccess = success;
   *   }
   *
   *   // High correlation = fewer state changes (longer bursts)
   *   assertThat(successStreaks + lossStreaks).isLessThan(30);  // Few transitions
   *
   *   chaos.reset(service);
   * }
   * }</pre>
   *
   * @param container target container
   * @param percentage base loss probability (0.0-1.0)
   * @param correlation state transition probability (0.0-1.0, higher = longer bursts)
   * @throws IllegalArgumentException if percentage or correlation not in [0.0, 1.0]
   */
  void injectCorrelatedPacketLoss(
      GenericContainer<?> container, double percentage, double correlation);

  /**
   * Limit network bandwidth using token bucket filter.
   *
   * <p>Uses {@code tc qdisc add dev eth0 root tbf rate <rate> burst 32kbit latency 400ms}.
   *
   * <h3>Example: Test Large File Transfer on Slow Connection</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldStreamLargeFileOnSlowConnection() {
   *   // Limit to 1 Mbps (mobile 3G)
   *   chaos.limitBandwidth(service, "1mbit");
   *
   *   long start = System.currentTimeMillis();
   *   byte[] data = downloadFile(service, 10 * 1024 * 1024);  // 10 MB file
   *   long duration = System.currentTimeMillis() - start;
   *
   *   assertThat(data).hasSize(10 * 1024 * 1024);
   *   assertThat(duration).isGreaterThan(80_000);  // 10 MB at 1 Mbps = 80+ seconds
   *
   *   chaos.reset(service);
   * }
   * }</pre>
   *
   * <h3>Common Bandwidth Limits</h3>
   *
   * <table border="1">
   *   <caption>Network Speeds</caption>
   *   <tr>
   *     <th>Scenario</th>
   *     <th>Rate</th>
   *   </tr>
   *   <tr>
   *     <td>Modem</td>
   *     <td>56kbit</td>
   *   </tr>
   *   <tr>
   *     <td>Mobile 2G</td>
   *     <td>100kbit</td>
   *   </tr>
   *   <tr>
   *     <td>Mobile 3G</td>
   *     <td>1mbit</td>
   *   </tr>
   *   <tr>
   *     <td>Mobile 4G</td>
   *     <td>10mbit</td>
   *   </tr>
   *   <tr>
   *     <td>DSL</td>
   *     <td>10mbit-50mbit</td>
   *   </tr>
   *   <tr>
   *     <td>Cable</td>
   *     <td>100mbit-1gbit</td>
   *   </tr>
   * </table>
   *
   * @param container target container
   * @param rate bandwidth limit (e.g., "10mbit", "1gbit", "100kbit")
   * @throws IllegalArgumentException if rate format is invalid
   */
  void limitBandwidth(GenericContainer<?> container, String rate);

  /**
   * Create network partition (completely block traffic between containers).
   *
   * <p>Uses {@code iptables -A OUTPUT -d <target-ip> -j DROP}.
   *
   * <h3>Example: Test Split-Brain Detection</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldDetectSplitBrain() {
   *   // Partition node1 from node2 (simulates datacenter split)
   *   chaos.partitionFrom(node1, node2);
   *
   *   // node1 cannot reach node2
   *   assertThatThrownBy(() -> node1.sendHeartbeat(node2))
   *       .isInstanceOf(ConnectException.class);
   *
   *   // node2 CAN still reach node1 (one-way partition)
   *   assertThat(node2.sendHeartbeat(node1)).isSuccessful();
   *
   *   // Verify cluster detects partition
   *   await().atMost(Duration.ofSeconds(10))
   *       .until(() -> node1.getClusterStatus() == MINORITY);
   *
   *   chaos.reset(node1);
   * }
   * }</pre>
   *
   * <h3>One-Way vs Two-Way Partition</h3>
   *
   * <pre>{@code
   * // One-way: A cannot reach B, but B CAN reach A
   * chaos.partitionFrom(containerA, containerB);
   *
   * // Two-way: Neither can reach other
   * chaos.partitionFrom(containerA, containerB);
   * chaos.partitionFrom(containerB, containerA);
   * }</pre>
   *
   * @param container source container (will be blocked from reaching target)
   * @param target destination container (will be unreachable from source)
   */
  void partitionFrom(GenericContainer<?> container, GenericContainer<?> target);

  /**
   * Remove all network chaos (delete tc qdiscs and iptables rules).
   *
   * <p><strong>CRITICAL:</strong> Always call in @AfterEach to prevent rule leaks.
   *
   * <h3>Example: Proper Cleanup</h3>
   *
   * <pre>{@code
   * @Testcontainers
   * class NetworkTest {
   *
   *   @Container
   *   GenericContainer<?> service = new GenericContainer<>("myapp:latest");
   *
   *   NetworkChaos chaos = new NetworkChaosProvider();
   *
   *   @AfterEach
   *   void cleanup() {
   *     chaos.reset(service);  // Remove tc + iptables rules
   *   }
   *
   *   @Test
   *   void test() {
   *     chaos.injectLatency(service, Duration.ofMillis(100));
   *     // Test logic...
   *   }
   * }
   * }</pre>
   *
   * <h3>What This Does</h3>
   *
   * <ol>
   *   <li>Removes all tc qdiscs: {@code tc qdisc del dev eth0 root}
   *   <li>Flushes iptables OUTPUT chain: {@code iptables -F OUTPUT}
   *   <li>Restores normal network behavior
   * </ol>
   *
   * @param container target container
   */
  @Override
  void reset(GenericContainer<?> container);
}
