/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.network.control.NetworkChaosController;
import com.macstab.chaos.redis.control.failover.FailoverHelper;
import com.macstab.chaos.redis.control.inspection.ConnectionInfo;
import com.macstab.chaos.redis.control.inspection.ConnectionInspector;
import com.macstab.chaos.redis.control.inspection.LettuceConnectionInspector;
import com.macstab.chaos.redis.control.lifecycle.ContainerController;
import com.macstab.chaos.redis.control.lifecycle.TestcontainerController;
import com.macstab.chaos.redis.control.role.ContainerRole;
import com.macstab.chaos.redis.control.role.RoleResolver;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Public facade for container control and connection inspection.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>✅ Connection inspection (identify container from Lettuce connection)
 *   <li>✅ Container lifecycle control (restart, kill, pause, resume)
 *   <li>✅ Failover simulation and testing
 *   <li>✅ Role-based container access (get master, get replica by index)
 *   <li>✅ Network chaos engineering (latency, packet loss, partitioning)
 *   <li>✅ Thread-safe (all components are thread-safe)
 * </ul>
 *
 * <p><strong>Unified API:</strong>
 *
 * <table border="1">
 *   <caption>ControlFacade API Overview</caption>
 *   <tr><th>Category</th><th>Methods</th></tr>
 *   <tr><td>Inspection</td><td>inspect(connection)</td></tr>
 *   <tr><td>Lifecycle</td><td>restart(), kill(), pause(), resume()</td></tr>
 *   <tr><td>Failover</td><td>triggerFailover(), findMaster()</td></tr>
 *   <tr><td>Role Access</td><td>getMaster(), getContainer(role)</td></tr>
 *   <tr><td>Network Chaos</td><td>network().injectLatency(), network().injectPacketLoss()</td></tr>
 * </table>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Create facade
 * ControlFacade control = ControlFacade.create(allContainers, containerIndexMap);
 *
 * // 1. Inspect connection
 * ConnectionInfo info = control.inspect(connection);
 * System.out.println("Connected to: " + info.role());
 *
 * // 2. Restart replica
 * control.restart(info.container());
 *
 * // 3. Trigger failover
 * Duration duration = control.triggerFailover();
 * System.out.println("Failover completed in: " + duration.toMillis() + "ms");
 *
 * // 4. Get new master
 * GenericContainer<?> newMaster = control.getMaster();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class ControlFacade {

  private final ConnectionInspector inspector;
  private final ContainerController controller;
  private final FailoverHelper failoverHelper;
  private final RoleResolver roleResolver;
  private final NetworkChaosController networkChaos;
  private final List<GenericContainer<?>> allContainers;

  /**
   * Creates a ControlFacade (use {@link #create} factory method instead).
   *
   * @param inspector connection inspector
   * @param controller container controller
   * @param failoverHelper failover helper
   * @param roleResolver role resolver
   * @param networkChaos network chaos controller
   * @param allContainers all containers
   */
  private ControlFacade(
      final ConnectionInspector inspector,
      final ContainerController controller,
      final FailoverHelper failoverHelper,
      final RoleResolver roleResolver,
      final NetworkChaosController networkChaos,
      final List<GenericContainer<?>> allContainers) {
    this.inspector = inspector;
    this.controller = controller;
    this.failoverHelper = failoverHelper;
    this.roleResolver = roleResolver;
    this.networkChaos = networkChaos;
    this.allContainers = allContainers;
  }

  /**
   * Creates a ControlFacade for a Sentinel cluster.
   *
   * @param allContainers all containers (master + replicas + sentinels)
   * @param containerIndexMap map of containers to their indices
   * @return ControlFacade instance (never null)
   * @throws NullPointerException if any parameter is null
   */
  public static ControlFacade create(
      final List<GenericContainer<?>> allContainers,
      final Map<GenericContainer<?>, Integer> containerIndexMap) {
    Objects.requireNonNull(allContainers, "allContainers");
    Objects.requireNonNull(containerIndexMap, "containerIndexMap");

    final RoleResolver roleResolver = new RoleResolver(containerIndexMap);
    final ConnectionInspector inspector =
        new LettuceConnectionInspector(allContainers, roleResolver);
    final ContainerController controller = new TestcontainerController();
    final FailoverHelper failoverHelper =
        new FailoverHelper(controller, roleResolver, allContainers);
    final NetworkChaosController networkChaos = new NetworkChaosController(allContainers);

    return new ControlFacade(
        inspector, controller, failoverHelper, roleResolver, networkChaos, allContainers);
  }

  // ==================== Connection Inspection ====================

  /**
   * Inspects a connection to determine which container it's using (auto-detection).
   *
   * <p><strong>Tier 1: Auto-Detection</strong> - Extracts endpoint from connection toString().
   *
   * @param connection Lettuce connection to inspect (never null)
   * @return connection info with role, container, and health status (never null)
   * @throws NullPointerException if connection is null
   * @throws IllegalStateException if connection is closed or cannot detect container
   */
  public ConnectionInfo inspect(final StatefulRedisConnection<?, ?> connection) {
    return inspector.inspect(connection);
  }

  /**
   * Inspects a connection with explicit container hint (100% reliable).
   *
   * <p><strong>Tier 2: Explicit Hint</strong> - Use when auto-detection fails.
   *
   * @param connection Lettuce connection to inspect (never null)
   * @param containerHint the container this connection is using (never null)
   * @return connection info with role, container, and health status (never null)
   * @throws NullPointerException if connection or containerHint is null
   * @throws IllegalStateException if connection is closed or container not running
   */
  public ConnectionInfo inspect(
      final StatefulRedisConnection<?, ?> connection, final GenericContainer<?> containerHint) {
    return inspector.inspect(connection, containerHint);
  }

  /**
   * Creates connection info manually without inspecting connection (full control).
   *
   * <p><strong>Tier 3: Manual</strong> - For edge cases or pre-computation.
   *
   * @param container the container to create info for (never null)
   * @param connectionDescription human-readable description (never null)
   * @return connection info with role, container, and description (never null)
   * @throws NullPointerException if container or connectionDescription is null
   * @throws IllegalStateException if container is not running
   */
  public ConnectionInfo inspectManual(
      final GenericContainer<?> container, final String connectionDescription) {
    return inspector.inspectManual(container, connectionDescription);
  }

  // ==================== Container Lifecycle ====================

  /**
   * Restarts a container (graceful stop + start).
   *
   * @param container the container to restart (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if restart fails
   */
  public void restart(final GenericContainer<?> container) {
    controller.restart(container);
  }

  /**
   * Kills a container (immediate termination, SIGKILL).
   *
   * @param container the container to kill (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if kill fails
   */
  public void kill(final GenericContainer<?> container) {
    controller.kill(container);
  }

  /**
   * Pauses a container (freezes all processes).
   *
   * @param container the container to pause (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if pause fails
   */
  public void pause(final GenericContainer<?> container) {
    controller.pause(container);
  }

  /**
   * Resumes a paused container (unfreezes processes).
   *
   * @param container the container to resume (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if resume fails or container not paused
   */
  public void resume(final GenericContainer<?> container) {
    controller.resume(container);
  }

  /**
   * Waits for a container to become ready (PING succeeds).
   *
   * @param container the container to wait for (never null)
   * @throws NullPointerException if container is null
   * @throws IllegalStateException if container doesn't become ready within 30 seconds
   */
  public void waitForReady(final GenericContainer<?> container) {
    controller.waitForReady(container);
  }

  /**
   * Waits for a container to become ready with custom timeout.
   *
   * @param container the container to wait for (never null)
   * @param timeout maximum wait time (never null)
   * @throws NullPointerException if container or timeout is null
   * @throws IllegalStateException if container doesn't become ready within timeout
   */
  public void waitForReady(final GenericContainer<?> container, final Duration timeout) {
    controller.waitForReady(container, timeout);
  }

  // ==================== Failover Simulation ====================

  /**
   * Triggers a failover by killing the current master.
   *
   * @return failover duration (time until new master elected)
   * @throws IllegalStateException if failover doesn't complete within 30 seconds
   */
  public Duration triggerFailover() {
    final GenericContainer<?> master = failoverHelper.findMaster();
    return failoverHelper.triggerFailover(master);
  }

  /**
   * Triggers a failover with custom timeout.
   *
   * @param timeout maximum wait time for failover completion
   * @return failover duration (time until new master elected)
   * @throws NullPointerException if timeout is null
   * @throws IllegalStateException if failover doesn't complete within timeout
   */
  public Duration triggerFailover(final Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    final GenericContainer<?> master = failoverHelper.findMaster();
    return failoverHelper.triggerFailover(master, timeout);
  }

  /**
   * Finds the current master container.
   *
   * @return master container (never null)
   * @throws IllegalStateException if no master found
   */
  public GenericContainer<?> getMaster() {
    return failoverHelper.findMaster();
  }

  /**
   * Finds all replica containers.
   *
   * @return list of replica containers (never null, may be empty)
   */
  public List<GenericContainer<?>> getReplicas() {
    return failoverHelper.findReplicas();
  }

  // ==================== Role-Based Access ====================

  /**
   * Gets a container by role.
   *
   * @param role the role to find (never null)
   * @return container with matching role (never null)
   * @throws NullPointerException if role is null
   * @throws IllegalStateException if no container has the role
   */
  public GenericContainer<?> getContainer(final ContainerRole role) {
    Objects.requireNonNull(role, "role");

    return allContainers.stream()
        .filter(GenericContainer::isRunning)
        .filter(container -> roleResolver.resolve(container).equals(role))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No container found with role: " + role));
  }

  // ==================== Network Chaos Engineering ====================

  /**
   * Returns the network chaos controller for chaos engineering operations.
   *
   * <p><strong>Capabilities:</strong>
   *
   * <ul>
   *   <li>🐌 Latency injection (simulate slow networks, cross-region replication)
   *   <li>📉 Packet loss injection (simulate unreliable networks)
   *   <li>📊 Jitter injection (simulate variable latency)
   *   <li>🚧 Network partitioning (simulate split-brain scenarios)
   * </ul>
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Simulate cross-region replication lag (80ms)
   * control.network().injectLatency(replicaEU, Duration.ofMillis(80));
   *
   * // Test if replication lag is acceptable
   * redisTemplate.opsForValue().set("key", "value");
   * Thread.sleep(100);
   * assertThat(getFromReplica("key")).isEqualTo("value");
   *
   * // Clean up
   * control.network().reset(replicaEU);
   * }</pre>
   *
   * <p><strong>Advanced Example (Network Partition):</strong>
   *
   * <pre>{@code
   * // Simulate split-brain scenario
   * control.network().partitionFrom(replica, master);
   *
   * // Test: Does Sentinel prevent split-brain?
   * assertThat(countMasters()).isEqualTo(1);
   *
   * // Clean up
   * control.network().reset(replica);
   * }</pre>
   *
   * @return network chaos controller (never null)
   */
  public NetworkChaosController network() {
    return networkChaos;
  }

  /**
   * Clears the role cache (e.g., after failover).
   *
   * <p>Use after topology changes to force re-inspection.
   */
  public void clearRoleCache() {
    roleResolver.clearCache();
  }
}
