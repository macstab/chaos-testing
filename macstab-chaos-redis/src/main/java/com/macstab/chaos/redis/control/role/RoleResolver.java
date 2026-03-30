/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.role;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.command.RedisCommandBuilder;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class RoleResolver {
  private final Map<GenericContainer<?>, Integer> containerIndexMap;
  private final Map<GenericContainer<?>, ContainerRole> cache = new ConcurrentHashMap<>();

  /**
   * Creates a RoleResolver with container-to-index mapping.
   *
   * @param containerIndexMap map of containers to their indices (0-based for replicas/sentinels)
   * @throws NullPointerException if containerIndexMap is null
   */
  public RoleResolver(final Map<GenericContainer<?>, Integer> containerIndexMap) {
    this.containerIndexMap = Objects.requireNonNull(containerIndexMap, "containerIndexMap");
  }

  /**
   * Resolves the role of a container (with caching).
   *
   * <p>First lookup checks cache. If not cached, queries Redis INFO command and stores result.
   *
   * @param container the container to resolve (never null)
   * @return container role (never null, returns {@link ContainerRole#UNKNOWN} on failure)
   * @throws NullPointerException if container is null
   */
  public ContainerRole resolve(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");
    return cache.computeIfAbsent(container, this::determineRole);
  }

  /**
   * Clears the role cache.
   *
   * <p>Use after failover or topology changes to force re-inspection.
   */
  public void clearCache() {
    cache.clear();
    log.debug("Role cache cleared");
  }

  /**
   * Clears the cache entry for a specific container.
   *
   * @param container the container to remove from cache (never null)
   * @throws NullPointerException if container is null
   */
  public void clearCache(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");
    cache.remove(container);
    log.debug("Role cache cleared for container: {}", container.getContainerId());
  }

  // ==================== Internal Role Detection ====================

  /**
   * Determines the role by querying Redis INFO command.
   *
   * @param container the container to inspect
   * @return detected role (never null)
   */
  private ContainerRole determineRole(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      log.warn("Container not running: {}", container.getContainerId());
      return ContainerRole.UNKNOWN;
    }

    try {
      // Determine container type by checking exposed ports (not mapped ports!)
      final boolean isSentinel = isSentinelContainer(container);

      if (isSentinel) {
        return resolveSentinelRole(container);
      }

      // Redis container - use internal Docker network IP (for DinD compatibility)
      final String internalIp = getInternalIpAddress(container);
      if (internalIp != null) {
        log.debug("Using internal IP {} for container {}", internalIp, container.getContainerId());
        final ContainerRole role = resolveRedisRole(internalIp, RedisCommandBuilder.DEFAULT_REDIS_PORT, container);
        return role;
      }

      // Fallback: Use host and mapped port (for non-networked containers)
      final String host = container.getHost();
      final int port = container.getMappedPort(RedisCommandBuilder.DEFAULT_REDIS_PORT);
      log.debug("Using host:port {}:{} for container {}", host, port, container.getContainerId());
      final ContainerRole role = resolveRedisRole(host, port, container);
      return role;

    } catch (Exception e) {
      log.error("Failed to determine role for container: {}", container.getContainerId(), e);
      return ContainerRole.UNKNOWN;
    }
  }

  /**
   * Gets the internal Docker network IP address of a container.
   *
   * <p>In Docker-in-Docker environments (devcontainer), containers cannot reach each other via
   * host:mappedPort. We must use the internal Docker network IP.
   *
   * @param container the container to inspect
   * @return internal IP address (e.g., "172.18.0.2"), or null if not on a Docker network
   */
  private String getInternalIpAddress(final GenericContainer<?> container) {
    try {
      return container.getContainerInfo().getNetworkSettings().getNetworks().values().stream()
          .findFirst()
          .map(network -> network.getIpAddress())
          .orElse(null);
    } catch (Exception e) {
      log.debug("Failed to get internal IP for container: {}", container.getContainerId(), e);
      return null;
    }
  }

  /**
   * Resolves Sentinel role by index from containerIndexMap.
   *
   * @param container the Sentinel container
   * @return SENTINEL_N role
   */
  private ContainerRole resolveSentinelRole(final GenericContainer<?> container) {
    final Integer index = containerIndexMap.get(container);
    if (index == null) {
      log.warn("Sentinel container not in index map: {}", container.getContainerId());
      return ContainerRole.UNKNOWN;
    }
    return ContainerRole.sentinelByIndex(index);
  }

  /**
   * Resolves Redis role (master or replica) by querying INFO replication.
   *
   * @param host Redis host
   * @param port Redis port
   * @param container the container being inspected
   * @return MASTER or REPLICA_N role
   */
  private ContainerRole resolveRedisRole(
      final String host, final int port, final GenericContainer<?> container) {

    log.debug(
        "Attempting to resolve role for container {} at {}:{}",
        container.getContainerId(),
        host,
        port);

    final RedisURI uri = RedisURI.builder().withHost(host).withPort(port).build();

    try (final RedisClient client = RedisClient.create(uri);
        final StatefulRedisConnection<String, String> conn = client.connect()) {

      final String info = conn.sync().info("replication");

      if (info.contains("role:master")) {
        log.info("Container is MASTER: {} ({}:{})", container.getContainerId(), host, port);
        return ContainerRole.MASTER;
      } else if (info.contains("role:slave")) {
        log.info("Container is REPLICA: {} ({}:{})", container.getContainerId(), host, port);
        return resolveReplicaRole(container);
      } else {
        log.warn("Unknown Redis role in INFO: {}", info);
        return ContainerRole.UNKNOWN;
      }

    } catch (Exception e) {
      log.error(
          "Failed to query Redis INFO at {}:{} for container {}: {}",
          host,
          port,
          container.getContainerId(),
          e.getMessage());
      return ContainerRole.UNKNOWN;
    }
  }

  /**
   * Resolves replica role by index from containerIndexMap.
   *
   * @param container the replica container
   * @return REPLICA_N role
   */
  private ContainerRole resolveReplicaRole(final GenericContainer<?> container) {
    final Integer index = containerIndexMap.get(container);
    if (index == null) {
      log.warn("Replica container not in index map: {}", container.getContainerId());
      return ContainerRole.UNKNOWN;
    }
    log.debug("Container is REPLICA_{}: {}", index, container.getContainerId());
    return ContainerRole.replicaByIndex(index);
  }

  /**
   * Checks if container is a Sentinel by examining exposed ports.
   *
   * @param container the container to check
   * @return true if container exposes port 26379
   */
  private boolean isSentinelContainer(final GenericContainer<?> container) {
    try {
      return container.getExposedPorts().contains(RedisCommandBuilder.DEFAULT_SENTINEL_PORT);
    } catch (Exception e) {
      log.debug("Failed to check if Sentinel container: {}", container.getContainerId(), e);
      return false;
    }
  }
}
