/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ConnectionChaos;
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
import lombok.extern.slf4j.Slf4j;

/**
 * Unified facade for all Redis cluster control operations during chaos testing.
 *
 * <p><strong>Capabilities:</strong>
 *
 * <ul>
 *   <li>Connection inspection (identify container from Lettuce connection)
 *   <li>Container lifecycle (restart, kill, pause, resume, waitForReady)
 *   <li>Failover simulation (trigger and measure election time)
 *   <li>Role-based container access (master, replica, sentinel)
 *   <li>Network chaos engineering (latency, packet loss, jitter, partitions)
 * </ul>
 *
 * <p><strong>Lifecycle:</strong> Created once per cluster via {@link #create(List, Map)} and reused
 * across test methods. Thread-safe; caches role resolution results.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * ControlFacade control = ControlFacade.create(allContainers, containerIndexMap);
 *
 * // Trigger failover and measure election time
 * Duration elapsed = control.triggerFailover();
 * assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
 *
 * // Inject network latency on replica
 * control.network().injectLatency(control.getContainer(ContainerRole.REPLICA_0), Duration.ofMillis(80));
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class ControlFacade {

  private final ConnectionInspector inspector;
  private final ContainerController controller;
  private final FailoverHelper failoverHelper;
  private final RoleResolver roleResolver;
  private final NetworkChaosController networkChaos;
  private final List<GenericContainer<?>> allContainers;

  /**
   * Lazily-resolved syscall+proxy connection-chaos provider. Loaded the first time {@link
   * #connection()} is called via {@link ServiceLoader} from {@code macstab-chaos-connection}.
   * Stored {@code volatile} so the resolution is published safely across threads after the first
   * read.
   */
  private volatile ConnectionChaos connectionChaos;

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

  /**
   * Creates a ControlFacade for a single standalone Redis container.
   *
   * <p>Simplified factory for standalone (non-cluster, non-sentinel) Redis instances. Creates a
   * minimal ControlFacade with index 0 assigned to the container.
   *
   * @param container standalone Redis container (never null)
   * @return ControlFacade instance (never null)
   * @throws NullPointerException if container is null
   */
  public static ControlFacade forStandalone(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");
    final List<GenericContainer<?>> containers = List.of(container);
    final Map<GenericContainer<?>, Integer> indexMap = Map.of(container, 0);
    return create(containers, indexMap);
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

  // ==================== Connection Chaos Engineering ====================

  /**
   * Returns the connection chaos provider for socket-syscall and proxy-level fault injection.
   *
   * <p><strong>Capabilities (composite — libchaos-net first, Toxiproxy fallback):</strong>
   *
   * <ul>
   *   <li>Per-syscall errno injection on {@code connect}, {@code bind}, {@code accept}, {@code
   *       send}, {@code recv}, {@code poll} (via {@code libchaos-net} {@code LD_PRELOAD})
   *   <li>UDP / unix-socket / DNS-level fault injection ({@code libchaos-net} only)
   *   <li>{@code addLatency}, {@code dropPackets}, {@code timeoutConnections}, {@code slowClose},
   *       {@code rejectConnections} — routed to {@code libchaos-net} by default
   *   <li>{@code limitBandwidth} — falls through to Toxiproxy (only mechanism that can model it)
   * </ul>
   *
   * <p><strong>Pre-flight contract.</strong> For the libchaos-net path to work, the target
   * container must have been prepared <em>before</em> {@code container.start()}. Use {@code
   * enableConnectionChaos=true} on {@link com.macstab.chaos.redis.annotation.RedisStandalone} or
   * {@link com.macstab.chaos.redis.annotation.RedisSentinel} to drive that preparation; the
   * factories invoke {@code LibchaosTransport(LibchaosLib.NET).prepare()} on every container they
   * create. Skipping preparation surfaces a clear {@code LibchaosNotPreparedException} at the call
   * site — there is no silent fallback for syscall-level verbs.
   *
   * <p><strong>Discovery:</strong> the provider is loaded lazily on first access. Resolution
   * prefers {@code CompositeConnectionChaos.standard()} from {@code macstab-chaos-connection}
   * (giving every verb both syscall-level and proxy-level layers with fall-through); when that
   * class is not on the classpath, {@link ServiceLoader} is used as a fallback.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Refuse new connections at the syscall level
   * control.connection().rejectConnections(container, "redis-master:6379");
   *
   * // Bandwidth shaping — transparently routed to Toxiproxy
   * control.connection().limitBandwidth(container, "redis-master:6379", 4096);
   *
   * // Cleanup
   * control.connection().removeAllToxics(container, "redis-master:6379");
   * }</pre>
   *
   * @return composite connection chaos provider (never null)
   * @throws IllegalStateException if {@code macstab-chaos-connection} is not on the classpath
   *     (typically means the user forgot {@code enableConnectionChaos=true} on the annotation, or
   *     the build dependency on {@code macstab-chaos-connection} is missing)
   */
  public ConnectionChaos connection() {
    ConnectionChaos local = connectionChaos;
    if (local == null) {
      synchronized (this) {
        local = connectionChaos;
        if (local == null) {
          local = resolveConnectionChaos();
          connectionChaos = local;
        }
      }
    }
    return local;
  }

  /**
   * Resolves a {@link ConnectionChaos} provider.
   *
   * <p>The resolution preference is, in order:
   *
   * <ol>
   *   <li><strong>Composite</strong> from {@code macstab-chaos-connection} — loaded reflectively
   *       via {@code CompositeConnectionChaos.standard()}. This gives every verb both layers
   *       (libchaos-net syscall-level + Toxiproxy proxy-level) with automatic fall-through, which
   *       is what {@code enableConnectionChaos=true} promises.
   *   <li><strong>ServiceLoader fallback</strong> — first registered {@link ConnectionChaos}
   *       provider on the classpath. Used when {@code chaos-connection} is absent but some other
   *       module supplies a provider.
   * </ol>
   *
   * @return resolved provider (never null)
   * @throws IllegalStateException if no provider can be resolved
   */
  private static ConnectionChaos resolveConnectionChaos() {
    try {
      final Class<?> compositeClass =
          Class.forName("com.macstab.chaos.connection.CompositeConnectionChaos");
      final Object composite = compositeClass.getMethod("standard").invoke(null);
      return (ConnectionChaos) composite;
    } catch (final ClassNotFoundException missing) {
      log.debug(
          "CompositeConnectionChaos not on classpath; falling back to ServiceLoader for ConnectionChaos");
    } catch (final ReflectiveOperationException reflective) {
      log.warn(
          "CompositeConnectionChaos.standard() invocation failed; falling back to ServiceLoader",
          reflective);
    }
    return ServiceLoader.load(ConnectionChaos.class)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No ConnectionChaos provider found on the classpath. Either set"
                        + " enableConnectionChaos=true on the Redis annotation, or add"
                        + " a build dependency on macstab-chaos-connection so that"
                        + " CompositeConnectionChaos is reachable from this module."));
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
