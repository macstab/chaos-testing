/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * Base interface for all chaos providers.
 *
 * <p>Defines common lifecycle operations (install tools, reset chaos, check support). All
 * chaos-specific interfaces extend this base interface.
 *
 * <p><strong>Lifecycle:</strong>
 *
 * <ol>
 *   <li>{@link #isSupported()} — Check if chaos type available on current system
 *   <li>{@link #installTools(GenericContainer)} — Install required tools (stress-ng, tc, etc.)
 *   <li>Chaos operations (inject latency, throttle CPU, etc.) — Interface-specific
 *   <li>{@link #reset(GenericContainer)} — Remove all chaos effects
 * </ol>
 *
 * <p><strong>Implementation Requirements:</strong>
 *
 * <ul>
 *   <li>{@code installTools()} must be <strong>idempotent</strong> (safe to call multiple times)
 *   <li>{@code reset()} must be <strong>idempotent</strong> (safe to call when no chaos active)
 *   <li>All implementations must validate {@code container.isRunning()} before operations
 *   <li>All implementations must use {@link java.util.Objects#requireNonNull} for parameters
 * </ul>
 *
 * <p><strong>Example Implementation:</strong>
 *
 * <pre>{@code
 * public class CgroupsCpuChaos implements CpuChaos {
 *     @Override
 *     public void installTools(GenericContainer<?> container) {
 *         Objects.requireNonNull(container, "container must not be null");
 *         if (!container.isRunning()) {
 *             throw new IllegalStateException("Container must be running");
 *         }
 *         // Install stress-ng (idempotent - checks if already installed)
 *         PackageInstaller.install(container, "stress-ng");
 *     }
 *
 *     @Override
 *     public void reset(GenericContainer<?> container) {
 *         // Kill all stress-ng processes
 *         // Reset cgroup cpu.max to "max 100000"
 *     }
 *
 *     @Override
 *     public boolean isSupported() {
 *         // Check if cgroups v2 available
 *         return Files.exists(Path.of("/sys/fs/cgroup/cgroup.controllers"));
 *     }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see CpuChaos
 * @see MemoryChaos
 * @see NetworkChaos
 */
public interface ChaosProvider {

  /**
   * Install required tools in container (stress-ng, tc, iptables, etc.).
   *
   * <p>Called automatically by {@link com.macstab.chaos.core.facade.ChaosController} on first use.
   * Implementations must be idempotent (check if tools already installed).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @Override
   * public void installTools(GenericContainer<?> container) {
   *     // Idempotent: PackageInstaller checks if already installed
   *     if (!PackageInstaller.isInstalled(container, "stress-ng")) {
   *         PackageInstaller.install(container, "stress-ng");
   *     }
   * }
   * }</pre>
   *
   * @param container target container (must be running)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if installation fails
   */
  void installTools(GenericContainer<?> container);

  /**
   * Reset all chaos effects to normal.
   *
   * <p>Removes all cgroup limits, kills stress processes, clears tc rules, resets network settings.
   * Must be idempotent (safe to call when no chaos active).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @Override
   * public void reset(GenericContainer<?> container) {
   *     // Reset cgroup limits
   *     writeCgroup(container, "cpu.max", "max 100000");
   *
   *     // Kill stress processes
   *     container.execInContainer("pkill", "-9", "stress-ng");
   * }
   * }</pre>
   *
   * @param container target container
   * @throws NullPointerException if {@code container} is null
   * @throws ChaosOperationFailedException if reset fails (non-critical)
   */
  void reset(GenericContainer<?> container);

  /**
   * Provider priority for {@link java.util.ServiceLoader} selection.
   *
   * <p>Lower value = higher priority (consistent with {@code jakarta.annotation.Priority} and
   * Spring {@code @Order}). When multiple providers are registered for the same chaos interface,
   * the one with the lowest priority value wins.
   *
   * <p><strong>Conventions:</strong>
   *
   * <ul>
   *   <li>{@code 0} — default for real implementations (do not override unless needed)
   *   <li>{@code 1..999} — user-defined overrides that should take precedence
   *   <li>{@code Integer.MAX_VALUE} — NoOp fallback implementations
   * </ul>
   *
   * @return priority value; lower = preferred
   */
  default int priority() {
    return 0;
  }

  /**
   * Check if this chaos type is supported on current system.
   *
   * <p><strong>Checks:</strong>
   *
   * <ul>
   *   <li>Linux kernel features available (cgroups v2, tc, namespaces)
   *   <li>Required kernel version (e.g., 3.10+ for cgroups v2)
   *   <li>Host capabilities (NET_ADMIN for network chaos)
   * </ul>
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @Override
   * public boolean isSupported() {
   *     // Check if cgroups v2 available
   *     return Files.exists(Path.of("/sys/fs/cgroup/cgroup.controllers"));
   * }
   * }</pre>
   *
   * @return {@code true} if chaos type supported, {@code false} otherwise
   */
  boolean isSupported();
}
