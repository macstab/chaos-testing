/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import com.macstab.chaos.core.util.ContainerIdFormatter;
import com.macstab.chaos.redis.exception.ClusterCreationException;

import lombok.extern.slf4j.Slf4j;

/**
 * Factory for Redis Sentinel cluster containers.
 *
 * <p><strong>Sentinel Topology:</strong>
 *
 * <pre>
 * Network: redis-sentinel-net
 *   ├─ redis-master      (port 6379, accepts writes)
 *   ├─ redis-replica1..N (port 6379, replicates master, read-only)
 *   ├─ sentinel1..M      (port 26379, monitors master, triggers failover)
 *   └─ ...
 * </pre>
 *
 * <p><strong>Design:</strong> Static utility class — all methods are static, no instances allowed.
 * Use {@link StandaloneContainerFactory} for single-instance Redis containers.
 *
 * <p><strong>Network Requirements:</strong> Sentinel clusters use Docker networks internally.
 * Sentinel nodes communicate via internal Docker IPs; clients connect via host-mapped ports.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * SentinelCluster cluster = SentinelContainerFactory.createSentinelCluster();
 * try {
 *   GenericContainer<?> sentinel = cluster.firstSentinel();
 *   // Connect via sentinel
 * } finally {
 *   cluster.stop();
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see RawSentinelCluster
 * @see SentinelCommandBuilder
 * @see StandaloneContainerFactory
 */
@Slf4j
public final class SentinelContainerFactory {

  private SentinelContainerFactory() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Creates a Redis Sentinel cluster with default configuration (2 replicas, 3 sentinels).
   *
   * <p><strong>Default topology:</strong> 1 master + 2 replicas + 3 sentinels = 6 containers.
   *
   * <p><strong>Startup time:</strong> ~20-30 seconds.
   *
   * @return Sentinel cluster with all containers started
   * @throws ClusterCreationException if any container fails to start
   */
  public static RawSentinelCluster createSentinelCluster() {
    return createSentinelCluster(2, 3, false);
  }

  /**
   * Creates a Redis Sentinel cluster with configurable replica and sentinel counts.
   *
   * @param replicaCount number of replicas (must be &ge; 1)
   * @param sentinelCount number of sentinels (must be &ge; 1)
   * @return Sentinel cluster with all containers started
   * @throws ClusterCreationException if any container fails to start
   * @throws IllegalArgumentException if replica or sentinel count &lt; 1
   */
  public static RawSentinelCluster createSentinelCluster(
      final int replicaCount, final int sentinelCount) {
    return createSentinelCluster(replicaCount, sentinelCount, false);
  }

  /**
   * Creates a Redis Sentinel cluster with configurable replica, sentinel counts, and network chaos.
   *
   * <p><strong>Network Chaos:</strong> If enabled, adds NET_ADMIN capability to all containers
   * (container-scoped only, does not affect host or other containers).
   *
   * @param replicaCount number of replicas (must be &ge; 1)
   * @param sentinelCount number of sentinels (must be &ge; 1)
   * @param enableNetworkChaos if true, adds NET_ADMIN capability for network chaos engineering
   * @return Sentinel cluster with all containers started
   * @throws ClusterCreationException if any container fails to start
   * @throws IllegalArgumentException if replica or sentinel count &lt; 1
   * @since 2.0
   */
  public static RawSentinelCluster createSentinelCluster(
      final int replicaCount, final int sentinelCount, final boolean enableNetworkChaos) {
    validateCounts(replicaCount, sentinelCount);

    final int quorum = calculateQuorum(sentinelCount);
    log.info(
        "🚀 Creating Sentinel cluster: {} replicas, {} sentinels, quorum={}, networkChaos={}",
        replicaCount,
        sentinelCount,
        quorum,
        enableNetworkChaos);

    final long startTime = System.currentTimeMillis();
    final Network network = Network.newNetwork();
    log.debug("✓ Network created: {}", network.getId());

    final GenericContainer<?> master = startMaster(network, enableNetworkChaos, startTime);
    final List<GenericContainer<?>> replicas =
        startReplicas(network, replicaCount, enableNetworkChaos);
    final String masterIp = getMasterIpAddress(master);
    log.debug("Master IP: {}", masterIp);
    final List<GenericContainer<?>> sentinels =
        startSentinels(network, sentinelCount, masterIp, quorum, enableNetworkChaos);

    waitForSentinelStabilization();
    log.debug("✓ Sentinel cluster stabilized");

    final long totalDuration = System.currentTimeMillis() - startTime;
    log.info(
        "✓ Sentinel cluster created in {}ms: 1 master + {} replicas + {} sentinels",
        totalDuration,
        replicaCount,
        sentinelCount);

    return new RawSentinelCluster(network, master, replicas, sentinels);
  }

  // ==================== Private helpers ====================

  private static void validateCounts(final int replicaCount, final int sentinelCount) {
    if (replicaCount < 1) {
      throw new IllegalArgumentException("Replica count must be at least 1, got: " + replicaCount);
    }
    if (sentinelCount < 1) {
      throw new IllegalArgumentException(
          "Sentinel count must be at least 1, got: " + sentinelCount);
    }
  }

  private static GenericContainer<?> startMaster(
      final Network network, final boolean enableNetworkChaos, final long startTime) {
    log.debug("Starting master node...");
    final GenericContainer<?> master =
        SentinelNodeFactory.createMaster(network, enableNetworkChaos);
    try {
      master.start();
      log.info(
          "✓ Master started: {} ({}ms)",
          ContainerIdFormatter.truncate(master.getContainerId()),
          System.currentTimeMillis() - startTime);
      return master;
    } catch (final Exception e) {
      log.error("✗ Master startup failed", e);
      throw new ClusterCreationException("Failed to start master", 1, 1, e);
    }
  }

  private static List<GenericContainer<?>> startReplicas(
      final Network network, final int replicaCount, final boolean enableNetworkChaos) {
    log.debug("Starting {} replica(s)...", replicaCount);
    final List<GenericContainer<?>> replicas = new ArrayList<>();
    for (int i = 1; i <= replicaCount; i++) {
      final long replicaStart = System.currentTimeMillis();
      final GenericContainer<?> replica =
          SentinelNodeFactory.createReplica(network, "redis-replica" + i, enableNetworkChaos);
      try {
        replica.start();
        log.info(
            "✓ Replica {}/{} started: {} ({}ms)",
            i,
            replicaCount,
            ContainerIdFormatter.truncate(replica.getContainerId()),
            System.currentTimeMillis() - replicaStart);
        replicas.add(replica);
      } catch (final Exception e) {
        log.error("✗ Replica {}/{} startup failed", i, replicaCount, e);
        throw new ClusterCreationException("Failed to start replica", i, replicaCount, e);
      }
    }
    return replicas;
  }

  private static List<GenericContainer<?>> startSentinels(
      final Network network,
      final int sentinelCount,
      final String masterIp,
      final int quorum,
      final boolean enableNetworkChaos) {
    log.debug("Starting {} sentinel(s) with quorum={}...", sentinelCount, quorum);
    final List<GenericContainer<?>> sentinels = new ArrayList<>();
    for (int i = 1; i <= sentinelCount; i++) {
      final long sentinelStart = System.currentTimeMillis();
      final GenericContainer<?> sentinel =
          SentinelNodeFactory.createSentinel(
              network, "sentinel" + i, masterIp, quorum, enableNetworkChaos);
      try {
        sentinel.start();
        log.info(
            "✓ Sentinel {}/{} started: {} ({}ms)",
            i,
            sentinelCount,
            ContainerIdFormatter.truncate(sentinel.getContainerId()),
            System.currentTimeMillis() - sentinelStart);
        sentinels.add(sentinel);
      } catch (final Exception e) {
        log.error("✗ Sentinel {}/{} startup failed", i, sentinelCount, e);
        throw new ClusterCreationException("Failed to start sentinel", i, sentinelCount, e);
      }
    }
    return sentinels;
  }

  /**
   * Extracts master container IP address from Docker network.
   *
   * @param master master container
   * @return IP address (e.g., "172.18.0.2")
   * @throws RuntimeException if IP cannot be determined
   */
  static String getMasterIpAddress(final GenericContainer<?> master) {
    return master.getContainerInfo().getNetworkSettings().getNetworks().values().stream()
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Master container has no network"))
        .getIpAddress();
  }

  /**
   * Calculates appropriate quorum for given sentinel count.
   *
   * <p>Formula: majority = (sentinels / 2) + 1
   *
   * <ul>
   *   <li>1 sentinel → quorum 1 (100%)
   *   <li>3 sentinels → quorum 2 (majority)
   *   <li>5 sentinels → quorum 3 (majority)
   * </ul>
   *
   * @param sentinelCount number of sentinels
   * @return quorum value
   */
  static int calculateQuorum(final int sentinelCount) {
    return (sentinelCount / 2) + 1;
  }

  /**
   * Waits for Sentinel cluster to stabilize.
   *
   * <p>Even after +monitor event, Sentinels need ~2s to synchronize internal state.
   */
  private static void waitForSentinelStabilization() {
    try {
      Thread.sleep(2000);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for Sentinel stabilization", e);
    }
  }
}
