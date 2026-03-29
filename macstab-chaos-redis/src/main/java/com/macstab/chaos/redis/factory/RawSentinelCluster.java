/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/**
 * Sentinel cluster holder (network + all containers).
 *
 * <p><strong>Topology:</strong>
 *
 * <pre>
 * Network: redis-sentinel-net
 *   ├─ redis-master   (port 6379, accepts writes)
 *   ├─ redis-replica1 (port 6379, replicates master, read-only)
 *   ├─ ...
 *   ├─ sentinel1      (port 26379, monitors master)
 *   └─ ...
 * </pre>
 *
 * <p><strong>Lifecycle:</strong> Caller must call {@code stop()} when done, or use
 * try-with-resources. Typically managed by {@code SentinelContainerExtension}.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * RawSentinelCluster cluster = SentinelContainerFactory.createSentinelCluster();
 * try {
 *   GenericContainer<?> sentinel = cluster.firstSentinel();
 *   String host = sentinel.getHost();
 *   Integer port = sentinel.getMappedPort(26379);
 *   // Connect via sentinel...
 * } finally {
 *   cluster.stop();
 * }
 * }</pre>
 *
 * @param network Docker network (shared by all containers)
 * @param master Redis master container
 * @param replicas Redis replica containers (typically 2)
 * @param sentinels Sentinel containers (typically 3)
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public record RawSentinelCluster(
    Network network,
    GenericContainer<?> master,
    List<GenericContainer<?>> replicas,
    List<GenericContainer<?>> sentinels)
    implements AutoCloseable {

  /** Canonical constructor with null validation. */
  public RawSentinelCluster {
    Objects.requireNonNull(network, "network");
    Objects.requireNonNull(master, "master");
    Objects.requireNonNull(replicas, "replicas");
    Objects.requireNonNull(sentinels, "sentinels");
  }

  /**
   * Stops all containers and closes the Docker network.
   *
   * <p>Call in {@code @AfterEach} or {@code @AfterAll}.
   */
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

  /**
   * Returns first Sentinel container (for client connection).
   *
   * <p>Use this to get Sentinel connection details:
   *
   * <pre>{@code
   * GenericContainer<?> sentinel = cluster.firstSentinel();
   * String host = sentinel.getHost();
   * Integer port = sentinel.getMappedPort(26379);
   * }</pre>
   *
   * @return first sentinel container
   * @throws IndexOutOfBoundsException if no sentinels exist
   */
  public GenericContainer<?> firstSentinel() {
    return sentinels.get(0);
  }
}
