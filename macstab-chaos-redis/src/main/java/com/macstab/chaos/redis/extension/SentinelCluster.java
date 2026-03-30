/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import com.macstab.chaos.redis.api.Endpoint;
import com.macstab.chaos.redis.api.SentinelRedis;
import com.macstab.chaos.redis.command.RedisCommandBuilder;
import com.macstab.chaos.redis.control.ControlFacade;
import com.macstab.chaos.redis.control.inspection.ConnectionInfo;
import com.macstab.chaos.redis.control.role.ContainerRole;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;

import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds all containers for a running Redis Sentinel cluster.
 *
 * <p>Bundles the Docker network, master, replicas, and sentinels into one cohesive unit with a
 * unified lifecycle (start/stop) and access to the {@link ControlFacade} for chaos operations,
 * failover simulation, and connection inspection.
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <h2>Architecture</h2>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>
 * SentinelCluster
 *   ├── master container       (accepts writes)
 *   ├── replica containers     (read-only, replicate master)
 *   ├── sentinel containers    (monitor master, coordinate failover)
 *   └── ControlFacade (lazy)   (connection inspection, lifecycle, failover)
 * </pre>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <h2>⚠️ Lifecycle: CloseableResource</h2>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p>Implements {@link ExtensionContext.Store.CloseableResource} so JUnit 5 automatically stops all
 * containers when the test class completes. Do not call {@link #stop()} manually unless you are
 * managing lifecycle outside JUnit.
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <h2>ControlFacade (lazy)</h2>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <p>{@link #getControl()} creates the {@link ControlFacade} on first access using double-checked
 * locking. It is safe to call from multiple threads.
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <h2>Quick Start</h2>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <pre>{@code
 * @RedisSentinel(id = "ha", replicas = 2, sentinels = 3, enableNetworkChaos = true)
 * class FailoverTest {
 *
 *   @Test
 *   void testFailover(SentinelCluster cluster) {
 *     // Inject latency on replica
 *     cluster.getControl().network().injectLatency(
 *         cluster.getReplicaContainers().get(0), Duration.ofMillis(100));
 *
 *     // Trigger failover
 *     Duration elapsed = cluster.triggerFailover();
 *     assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class SentinelCluster implements ExtensionContext.Store.CloseableResource {

  private final Network network;
  private final GenericContainer<?> master;
  private final List<GenericContainer<?>> replicas;
  private final List<GenericContainer<?>> sentinels;
  private final String masterName;

  /** Lazily initialized — created on first {@link #getControl()} call. */
  private final AtomicReference<ControlFacade> controlFacade = new AtomicReference<>();

  /**
   * Create a Sentinel cluster holder.
   *
   * @param network Docker network shared by all containers
   * @param master master Redis container
   * @param replicas replica containers (read-only, immutable copy taken)
   * @param sentinels sentinel containers (immutable copy taken)
   * @param masterName sentinel master name (e.g., "mymaster")
   */
  public SentinelCluster(
      final Network network,
      final GenericContainer<?> master,
      final List<GenericContainer<?>> replicas,
      final List<GenericContainer<?>> sentinels,
      final String masterName) {
    this.network = Objects.requireNonNull(network, "network");
    this.master = Objects.requireNonNull(master, "master");
    this.replicas = List.copyOf(Objects.requireNonNull(replicas, "replicas"));
    this.sentinels = List.copyOf(Objects.requireNonNull(sentinels, "sentinels"));
    this.masterName = Objects.requireNonNull(masterName, "masterName");
  }

  // ==================== Lifecycle ====================

  /** Start all containers (master → replicas → sentinels). */
  public void start() {
    master.start();
    replicas.forEach(GenericContainer::start);
    sentinels.forEach(GenericContainer::start);
  }

  /** Stop all containers and close the Docker network (sentinels → replicas → master → network). */
  public void stop() {
    sentinels.forEach(GenericContainer::stop);
    replicas.forEach(GenericContainer::stop);
    master.stop();
    network.close();
  }

  @Override
  public void close() {
    stop();
  }

  // ==================== Container Access ====================

  /**
   * @return the master container
   */
  public GenericContainer<?> getMasterContainer() {
    return master;
  }

  /**
   * @return immutable list of replica containers
   */
  public List<GenericContainer<?>> getReplicaContainers() {
    return replicas;
  }

  /**
   * @return immutable list of sentinel containers
   */
  public List<GenericContainer<?>> getSentinelContainers() {
    return sentinels;
  }

  /**
   * @return Docker network shared by all containers
   */
  public Network getNetwork() {
    return network;
  }

  /**
   * @return sentinel master name (e.g., "mymaster")
   */
  public String getMasterName() {
    return masterName;
  }

  // ==================== Connection Info ====================

  /**
   * Host of the current master (dynamic — supports failover).
   *
   * @return master host address
   */
  public String getMasterHost() {
    return getControl().getMaster().getHost();
  }

  /**
   * Port of the current master (dynamic — supports failover).
   *
   * @return master port
   */
  public int getMasterPort() {
    return getControl().getMaster().getMappedPort(RedisCommandBuilder.DEFAULT_REDIS_PORT);
  }

  /**
   * @return master connection info (host + port)
   */
  public RedisConnectionInfo getMaster() {
    return new RedisConnectionInfo(getMasterHost(), getMasterPort());
  }

  /**
   * @return connection info for all replicas
   */
  public List<RedisConnectionInfo> getReplicas() {
    return replicas.stream()
        .map(r -> new RedisConnectionInfo(r.getHost(), r.getMappedPort(RedisCommandBuilder.DEFAULT_REDIS_PORT)))
        .toList();
  }

  /**
   * @return connection info for all sentinels
   */
  public List<RedisConnectionInfo> getSentinels() {
    return sentinels.stream()
        .map(s -> new RedisConnectionInfo(s.getHost(), s.getMappedPort(RedisCommandBuilder.DEFAULT_SENTINEL_PORT)))
        .toList();
  }

  /**
   * @return Lettuce RedisURI for the current master
   */
  public RedisURI getMasterURI() {
    return RedisURI.builder().withHost(getMasterHost()).withPort(getMasterPort()).build();
  }

  /**
   * @return Lettuce RedisURIs for all sentinels
   */
  public List<RedisURI> getSentinelURIs() {
    return getSentinels().stream()
        .map(s -> RedisURI.builder().withHost(s.getHost()).withPort(s.getPort()).build())
        .toList();
  }

  /**
   * Converts to the public {@link SentinelRedis} connection-info record.
   *
   * <p>Used for parameter injection ({@code List<SentinelRedis>}) and {@code
   * RedisSentinel.INSTANCE.get(id)}.
   *
   * @return immutable SentinelRedis connection info
   */
  public SentinelRedis toSentinelRedis() {
    final List<Endpoint> sentinelEndpoints =
        sentinels.stream().map(s -> new Endpoint(s.getHost(), s.getMappedPort(RedisCommandBuilder.DEFAULT_SENTINEL_PORT))).toList();
    final List<Endpoint> replicaEndpoints =
        replicas.stream().map(r -> new Endpoint(r.getHost(), r.getMappedPort(RedisCommandBuilder.DEFAULT_REDIS_PORT))).toList();
    return new SentinelRedis(
        getMasterHost(), getMasterPort(), masterName, sentinelEndpoints, replicaEndpoints);
  }

  // ==================== Control & Chaos ====================

  /**
   * Returns the {@link ControlFacade} for chaos operations, failover, and connection inspection.
   *
   * <p>Initialized lazily on first call (thread-safe via {@link AtomicReference} + synchronized).
   *
   * @return control facade (never null)
   */
  public ControlFacade getControl() {
    ControlFacade facade = controlFacade.get();
    if (facade == null) {
      synchronized (this) {
        facade = controlFacade.get();
        if (facade == null) {
          facade = ControlFacade.create(getAllContainers(), buildContainerIndexMap());
          controlFacade.set(facade);
        }
      }
    }
    return facade;
  }

  /**
   * Inspect a Lettuce connection to determine which container it is using.
   *
   * @param connection Lettuce connection — must not be null
   * @return connection info with role, container, and health status
   */
  public ConnectionInfo inspect(final StatefulRedisConnection<?, ?> connection) {
    return getControl().inspect(connection);
  }

  /**
   * Restart the container with the given role.
   *
   * @param role container role — must not be null
   */
  public void restart(final ContainerRole role) {
    getControl().restart(getControl().getContainer(role));
  }

  /**
   * Trigger a failover by killing the current master.
   *
   * @return duration until new master is elected
   * @throws IllegalStateException if failover does not complete within 30 seconds
   */
  public Duration triggerFailover() {
    return getControl().triggerFailover();
  }

  /**
   * Get the container with the given role.
   *
   * @param role container role — must not be null
   * @return matching container
   * @throws IllegalStateException if no container has the role
   */
  public GenericContainer<?> getContainer(final ContainerRole role) {
    return getControl().getContainer(role);
  }

  // ==================== Private Helpers ====================

  private List<GenericContainer<?>> getAllContainers() {
    final List<GenericContainer<?>> all = new ArrayList<>();
    all.add(master);
    all.addAll(replicas);
    all.addAll(sentinels);
    return List.copyOf(all);
  }

  private Map<GenericContainer<?>, Integer> buildContainerIndexMap() {
    final Map<GenericContainer<?>, Integer> map = new LinkedHashMap<>();
    for (int i = 0; i < replicas.size(); i++) {
      map.put(replicas.get(i), i);
    }
    for (int i = 0; i < sentinels.size(); i++) {
      map.put(sentinels.get(i), i);
    }
    return Map.copyOf(map);
  }
}
