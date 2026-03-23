/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;

/**
 * CPU chaos injection interface.
 *
 * <p>Provides CPU throttling and stress injection using Linux cgroups v2 ({@code cpu.max}) and
 * {@code stress-ng}.
 *
 * <p><strong>Default Implementation:</strong> {@link com.macstab.chaos.core.defaults.NoOpCpuChaos}
 * throws {@link ChaosProviderNotFoundException} with helpful dependency message.
 *
 * <p><strong>Real Implementation:</strong> Add dependency to enable CPU chaos:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-cpu:1.0.0")
 * }</pre>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Via ChaosController facade
 * ChaosController chaos = new ChaosController(container);
 * chaos.cpu().throttle(50);           // Limit to 50% of 1 CPU core
 * chaos.cpu().stress(4);              // Spawn 4 CPU-bound workers
 *
 * // Direct instantiation (if implementation on classpath)
 * CpuChaos cpuChaos = ChaosProviderRegistry.getCpuChaos();
 * cpuChaos.throttle(container, 50);
 * }</pre>
 *
 * <p><strong>Implementation Details:</strong>
 *
 * <ul>
 *   <li><strong>CPU Throttling:</strong> Uses cgroups v2 {@code cpu.max} to limit CPU bandwidth
 *   <li><strong>CPU Stress:</strong> Spawns {@code stress-ng --cpu <workers>} processes
 *   <li><strong>Auto-Install:</strong> Installs {@code stress-ng} if not present (via {@link
 *       com.macstab.chaos.core.util.PackageInstaller})
 *   <li><strong>Kernel Requirement:</strong> Linux kernel 3.10+ (cgroups v2 support)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosProvider
 * @see com.macstab.chaos.core.facade.ChaosController
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
