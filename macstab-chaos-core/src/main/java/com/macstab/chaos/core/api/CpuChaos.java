/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * CPU-level chaos using cgroups throttling and stress-ng.
 *
 * <p>Simulates CPU contention, saturation, and throttling using Linux cgroups v2 and stress-ng.
 *
 * <h2>How It Works</h2>
 *
 * <p><strong>CPU Throttling (cgroups v2):</strong>
 *
 * <pre>
 * Write quota to: /sys/fs/cgroup/.../cpu.max
 * Format: "&lt;quota_us&gt; &lt;period_us&gt;"
 * Example: "50000 100000" = 50% (50ms per 100ms period)
 * </pre>
 *
 * <p><strong>CPU Stress (stress-ng):</strong>
 *
 * <pre>
 * Command: stress-ng --cpu &lt;workers&gt; --timeout &lt;duration&gt;
 * Effect: Each worker consumes 100% of 1 CPU core
 * </pre>
 *
 * <h2>Complete Example: Redis Under CPU Pressure</h2>
 *
 * <pre>{@code
 * @Test
 * void shouldHandleSlowCommandsUnderCpuPressure() {
 *   CpuChaos chaos = new CpuChaosProvider();
 *
 *   // Baseline: Verify fast responses
 *   long baseline = measureLatency(() -> redis.get("key"));
 *   assertThat(baseline).isLessThan(5);  // <5ms
 *
 *   // Inject chaos: Throttle to 25% CPU
 *   chaos.throttle(redis, 25);
 *
 *   // Verify: Commands slower but functional
 *   long throttled = measureLatency(() -> redis.get("key"));
 *   assertThat(throttled).isBetween(15, 25);  // ~4x slower
 *
 *   // Application should handle gracefully (not timeout)
 *   assertThat(redis.get("key")).isNotNull();
 *
 *   chaos.reset(redis);
 * }
 * }</pre>
 *
 * <h2>Testing Patterns</h2>
 *
 * <h3>Pattern 1: CPU Throttling (Quota Limit)</h3>
 *
 * <pre>{@code
 * @Test
 * void testCpuThrottling() {
 *   chaos.throttle(app, 50);  // 50% CPU limit
 *
 *   // Verify: Slower but functional
 *   long duration = measureTaskTime();
 *   assertThat(duration).isGreaterThan(baseline * 1.8);  // ~2x slower
 * }
 * }</pre>
 *
 * <h3>Pattern 2: CPU Saturation (stress-ng)</h3>
 *
 * <pre>{@code
 * @Test
 * void testCpuSaturation() {
 *   chaos.stress(app, 4);  // 4 CPU workers (400% load)
 *
 *   // Application should queue work, not crash
 *   assertThat(app.processRequest()).isSuccessful();
 * }
 * }</pre>
 *
 * <h2>Common Use Cases</h2>
 *
 * <ul>
 *   <li>Noisy neighbor scenarios (throttle to 25-50%)
 *   <li>Batch job CPU burst (stress for 30s)
 *   <li>Redis slow commands (throttle AOF rewrite)
 *   <li>Thread pool saturation (stress + observe queuing)
 * </ul>
 *
 * <h2>Platform Requirements</h2>
 *
 * <ul>
 *   <li>Linux kernel 3.10+ (cgroups v2)
 *   <li>stress-ng (auto-installs if missing)
 *   <li>Supported: Ubuntu, Debian, Alpine, Fedora, RHEL
 * </ul>
 *
 * <h2>Implementation</h2>
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-cpu:1.0.0")
 * CpuChaos chaos = new CpuChaosProvider();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosProvider
 */
public interface CpuChaos extends ChaosProvider {

  /**
   * Throttle container CPU to percentage of 1 core.
   *
   * <p>Uses cgroups v2 {@code cpu.max} to limit CPU bandwidth. For example, 50% throttle writes
   * {@code "50000 100000"} to {@code cpu.max} (50ms quota per 100ms period).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * chaos.cpu().throttle(container, 50);  // Limit to 50% of 1 CPU core
   *
   * // Container processes can now use max 50% CPU
   * // Observed behavior:
   * // - CPU-bound tasks run at half speed
   * // - Multi-threaded apps limited by quota (not parallelism)
   * }</pre>
   *
   * <p><strong>Implementation (cgroups v2):</strong>
   *
   * <pre>
   * File: /sys/fs/cgroup/system.slice/docker-&lt;container-id&gt;.scope/cpu.max
   * Content: "50000 100000"  (50% = 50ms per 100ms period)
   * </pre>
   *
   * <p><strong>Use Cases:</strong>
   *
   * <ul>
   *   <li>Simulate CPU contention (noisy neighbor scenarios)
   *   <li>Test application behavior under CPU pressure
   *   <li>Verify graceful degradation (e.g., Redis slow commands)
   * </ul>
   *
   * @param container target container (must be running)
   * @param percentage CPU percentage (1-100, where 100 = 1 full CPU core)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code percentage} not in [1, 100]
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if cgroups write fails
   */
  void throttle(GenericContainer<?> container, int percentage);

  /**
   * Inject CPU stress by spawning worker processes.
   *
   * <p>Uses {@code stress-ng --cpu <workers>} to consume CPU cycles. Stress processes run
   * indefinitely until {@link #reset(GenericContainer)} is called or container stops.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * chaos.cpu().stress(container, 4);  // Spawn 4 CPU-bound workers
   *
   * // Each worker consumes 100% of 1 CPU core
   * // Total CPU usage: min(4 cores, available cores)
   * }</pre>
   *
   * <p><strong>Auto-Install:</strong> If {@code stress-ng} not present, automatically installs via
   * {@link com.macstab.chaos.core.util.PackageInstaller} (works on Debian, Alpine, Fedora, etc.).
   *
   * <p><strong>Use Cases:</strong>
   *
   * <ul>
   *   <li>Test scheduler behavior under high CPU load
   *   <li>Verify thread pool exhaustion scenarios
   *   <li>Simulate CPU saturation (e.g., Redis AOF rewrite under load)
   * </ul>
   *
   * @param container target container
   * @param workers number of CPU worker processes (must be ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stress(GenericContainer<?> container, int workers);

  /**
   * Inject CPU stress with automatic timeout.
   *
   * <p>Stress processes automatically terminate after {@code duration}. Useful for temporary CPU
   * spikes (e.g., simulate batch job CPU burst).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Stress CPU for 30 seconds, then auto-stop
   * chaos.cpu().stress(container, 4, Duration.ofSeconds(30));
   *
   * // Equivalent to:
   * // stress-ng --cpu 4 --timeout 30s
   * }</pre>
   *
   * @param container target container
   * @param workers number of CPU workers
   * @param duration stress duration (stress-ng {@code --timeout} parameter)
   * @throws NullPointerException if {@code container} or {@code duration} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1 or {@code duration} &le; 0
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails
   */
  void stress(GenericContainer<?> container, int workers, Duration duration);

  /**
   * Get current CPU usage percentage for container.
   *
   * <p>Reads from cgroups {@code cpu.stat} (usage_usec field) and calculates percentage relative to
   * 1 CPU core.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * chaos.cpu().stress(container, 2);  // Spawn 2 workers
   *
   * // Wait for stress to ramp up
   * await().atMost(5, SECONDS)
   *     .until(() -> chaos.cpu().getCurrentUsage(container) > 80);
   *
   * // Observed: ~200% usage (2 workers × 100% each)
   * // Reported: Capped at 100% (relative to 1 core baseline)
   * }</pre>
   *
   * <p><strong>Note:</strong> Returns percentage relative to 1 CPU core baseline. Multi-core usage
   * may exceed 100% in absolute terms but is normalized to [0, 100] range.
   *
   * @param container target container
   * @return CPU usage percentage (0-100)
   * @throws NullPointerException if {@code container} is null
   * @throws ChaosOperationFailedException if cgroups read fails
   */
  int getCurrentUsage(GenericContainer<?> container);
}
