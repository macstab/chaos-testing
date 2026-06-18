/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.model.MemoryPressureInfo;

/**
 * Memory chaos injection interface.
 *
 * <p>Provides memory limits, pressure thresholds, and stress injection using Linux cgroups v2
 * ({@code memory.*} controllers) and {@code stress-ng}.
 *
 * <p><strong>Default Implementation:</strong> {@link
 * com.macstab.chaos.core.defaults.NoOpMemoryChaos}
 *
 * <p><strong>Real Implementation:</strong> Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-memory:1.0.0")
 * }</pre>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * ChaosController chaos = new ChaosController(container);
 * chaos.memory().setLimit("512M");         // Hard limit (OOM killer triggers)
 * chaos.memory().setPressure("400M");      // Soft limit (throttling starts)
 * chaos.memory().stress("300M");           // Allocate 300MB (stress test)
 *
 * // Monitor pressure
 * MemoryPressureInfo pressure = chaos.memory().getPressure(container);
 * System.out.println("Memory pressure (10s avg): " + pressure.some10s() + "%");
 * }</pre>
 *
 * <p><strong>Kernel Mechanisms:</strong>
 *
 * <ul>
 *   <li><strong>{@code memory.max}:</strong> Hard limit → OOM killer when exceeded
 *   <li><strong>{@code memory.high}:</strong> Soft limit → kernel reclaims pages (throttling)
 *   <li><strong>{@code memory.pressure}:</strong> PSI (Pressure Stall Information) metrics
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosProvider
 * @see MemoryPressureInfo
 */
public interface MemoryChaos extends ChaosProvider {

  /**
   * Set hard memory limit (triggers OOM killer when exceeded).
   *
   * <p>Uses cgroups v2 {@code memory.max}. When container memory usage exceeds this limit, the
   * Linux OOM killer terminates processes in the container.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * chaos.memory().setLimit(container, "512M");  // 512 megabytes hard limit
   *
   * // If container allocates >512MB → OOM killer triggered
   * // Redis behavior: Process killed, container may restart (depends on restart policy)
   * }</pre>
   *
   * <p><strong>Supported Units:</strong> {@code K, M, G} (case-insensitive)
   *
   * <ul>
   *   <li>{@code "512M"} → 512 megabytes (512 × 1024 × 1024 bytes)
   *   <li>{@code "1G"} → 1 gigabyte
   *   <li>{@code "2048K"} → 2 megabytes
   * </ul>
   *
   * <p><strong>Use Cases:</strong>
   *
   * <ul>
   *   <li>Test Redis maxmemory eviction policies
   *   <li>Verify OOM recovery (does app reconnect after restart?)
   *   <li>Simulate memory-constrained environments (Kubernetes pod limits)
   * </ul>
   *
   * @param container target container
   * @param limit memory limit (e.g., "512M", "1G")
   * @throws NullPointerException if {@code container} or {@code limit} is null
   * @throws IllegalArgumentException if {@code limit} format invalid
   * @throws ChaosOperationFailedException if cgroups write fails
   */
  void setLimit(GenericContainer<?> container, String limit);

  /**
   * Set memory pressure threshold (throttles before OOM).
   *
   * <p>Uses cgroups v2 {@code memory.high}. When container memory usage exceeds this threshold, the
   * kernel aggressively reclaims memory pages, causing throttling (increased latency).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * chaos.memory().setLimit("512M");       // Hard limit (OOM)
   * chaos.memory().setPressure("400M");    // Soft limit (throttle)
   *
   * // Behavior:
   * // 0-400MB   → Normal performance
   * // 400-512MB → Throttling (page reclaim, increased latency)
   * // >512MB    → OOM killer
   * }</pre>
   *
   * <p><strong>Observed Effects:</strong>
   *
   * <ul>
   *   <li>Increased command latency (Redis SET/GET slower)
   *   <li>Page faults (kernel reclaims pages, reloads on access)
   *   <li>Pressure stall metrics increase (see {@link #getPressure(GenericContainer)})
   * </ul>
   *
   * @param container target container
   * @param threshold memory threshold (e.g., "400M")
   * @throws NullPointerException if {@code container} or {@code threshold} is null
   * @throws IllegalArgumentException if {@code threshold} format invalid
   * @throws ChaosOperationFailedException if cgroups write fails
   */
  void setPressure(GenericContainer<?> container, String threshold);

  /**
   * Inject memory stress by allocating and writing to memory.
   *
   * <p>Uses {@code stress-ng --vm 1 --vm-bytes <size>} to allocate memory and continuously write to
   * it (prevents kernel from reclaiming unused pages).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * chaos.memory().setLimit("512M");
   * chaos.memory().stress("300M");  // Allocate 300MB
   *
   * // Observed: Memory usage jumps to ~300MB
   * // Remaining headroom: 512MB - 300MB = 212MB for application
   * }</pre>
   *
   * <p><strong>Use Cases:</strong>
   *
   * <ul>
   *   <li>Test application behavior under memory pressure
   *   <li>Trigger maxmemory eviction in Redis
   *   <li>Verify connection pool limits under memory constraints
   * </ul>
   *
   * @param container target container
   * @param size memory to allocate (e.g., "300M")
   * @throws NullPointerException if {@code container} or {@code size} is null
   * @throws IllegalArgumentException if {@code size} format invalid
   * @throws ChaosOperationFailedException if stress-ng fails
   */
  void stress(GenericContainer<?> container, String size);

  /**
   * Get current memory usage in bytes.
   *
   * <p>Reads from cgroups v2 {@code memory.current} (total memory used by container, including
   * cache).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * chaos.memory().stress(container, "300M");
   *
   * long usage = chaos.memory().getCurrentUsage(container);
   * System.out.println("Memory usage: " + (usage / 1024 / 1024) + " MB");
   * // Output: Memory usage: ~300 MB
   * }</pre>
   *
   * @param container target container
   * @return current memory usage (bytes)
   * @throws NullPointerException if {@code container} is null
   * @throws ChaosOperationFailedException if cgroups read fails
   */
  long getCurrentUsage(GenericContainer<?> container);

  /**
   * Get memory pressure stall information (PSI).
   *
   * <p>Reads from cgroups v2 {@code memory.pressure}, which provides Pressure Stall Information
   * (PSI) metrics indicating how much time tasks were stalled due to memory scarcity.
   *
   * <p><strong>PSI Metrics:</strong>
   *
   * <ul>
   *   <li><strong>some:</strong> Percentage of time at least one task was stalled
   *   <li><strong>full:</strong> Percentage of time all tasks were stalled
   *   <li>Reported over 10s, 60s, 300s windows
   * </ul>
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * chaos.memory().setPressure("400M");
   * chaos.memory().stress("450M");  // Exceed pressure threshold
   *
   * MemoryPressureInfo psi = chaos.memory().getPressure(container);
   * System.out.println("Some pressure (10s): " + psi.some10s() + "%");
   * System.out.println("Full pressure (10s): " + psi.full10s() + "%");
   *
   * // Output:
   * // Some pressure (10s): 23.45%  (tasks stalled 23% of time)
   * // Full pressure (10s): 5.12%   (all tasks stalled 5% of time)
   * }</pre>
   *
   * @param container target container
   * @return pressure stall information
   * @throws NullPointerException if {@code container} is null
   * @throws ChaosOperationFailedException if cgroups read fails
   */
  MemoryPressureInfo getPressure(GenericContainer<?> container);
}
