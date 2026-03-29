/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Factory for individual Redis Sentinel cluster node containers.
 *
 * <p>Extracted from {@link SentinelContainerFactory} to keep that class under 200 lines. Each
 * method returns a configured but <strong>not started</strong> container.
 *
 * <p><strong>Responsibilities:</strong>
 *
 * <ul>
 *   <li>Create master, replica, and sentinel node containers
 *   <li>Apply network aliases, wait strategies, and startup timeouts
 *   <li>Conditionally add NET_ADMIN capability for network chaos
 *   <li>Configure extra hosts for Docker-in-Docker compatibility
 * </ul>
 *
 * <p><strong>Design:</strong> Static utility — no instances allowed.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class SentinelNodeFactory {

  private SentinelNodeFactory() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Creates a Redis master node container (not started).
   *
   * <p>Network alias: {@code redis-master}, port 6379, protected-mode off.
   *
   * @param network Docker network for the cluster
   * @param enableNetworkChaos if {@code true}, adds NET_ADMIN capability
   * @return configured master container
   */
  public static GenericContainer<?> createMaster(
      final Network network, final boolean enableNetworkChaos) {
    return new GenericContainer<>(StandaloneContainerFactory.REDIS_IMAGE)
        .withNetwork(network)
        .withNetworkAliases("redis-master")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--protected-mode", "no")
        .withCreateContainerCmdModifier(cmd -> applyHostConfig(cmd, enableNetworkChaos))
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
        .withStartupTimeout(StandaloneContainerFactory.DEFAULT_STARTUP_TIMEOUT);
  }

  /**
   * Creates a Redis replica node container (not started).
   *
   * <p>Connects to {@code redis-master:6379} via Docker network alias.
   *
   * @param network Docker network for the cluster
   * @param alias network alias for this replica (e.g., {@code redis-replica1})
   * @param enableNetworkChaos if {@code true}, adds NET_ADMIN capability
   * @return configured replica container
   */
  public static GenericContainer<?> createReplica(
      final Network network, final String alias, final boolean enableNetworkChaos) {
    return new GenericContainer<>(StandaloneContainerFactory.REDIS_IMAGE)
        .withNetwork(network)
        .withNetworkAliases(alias)
        .withExposedPorts(6379)
        .withCommand(
            "redis-server", "--protected-mode", "no", "--replicaof", "redis-master", "6379")
        .withCreateContainerCmdModifier(cmd -> applyHostConfig(cmd, enableNetworkChaos))
        .waitingFor(Wait.forLogMessage(".*MASTER <-> REPLICA sync: Finished with success.*\\n", 1))
        .withStartupTimeout(StandaloneContainerFactory.DEFAULT_STARTUP_TIMEOUT);
  }

  /**
   * Creates a Redis Sentinel node container (not started).
   *
   * <p>Port 26379. Monitors the master at {@code masterIp:6379} with the given quorum.
   *
   * @param network Docker network for the cluster
   * @param alias network alias for this sentinel (e.g., {@code sentinel1})
   * @param masterIp internal Docker IP of the master container
   * @param quorum sentinel quorum (majority vote threshold)
   * @param enableNetworkChaos if {@code true}, adds NET_ADMIN capability
   * @return configured sentinel container
   */
  public static GenericContainer<?> createSentinel(
      final Network network,
      final String alias,
      final String masterIp,
      final int quorum,
      final boolean enableNetworkChaos) {
    final String sentinelCommand =
        SentinelCommandBuilder.buildSentinelCommandWithoutAnnounce(masterIp, quorum);
    return new GenericContainer<>(StandaloneContainerFactory.REDIS_IMAGE)
        .withNetwork(network)
        .withNetworkAliases(alias)
        .withExposedPorts(26379)
        .withCreateContainerCmdModifier(cmd -> applyHostConfig(cmd, enableNetworkChaos))
        .withCommand(Shell.SH, Shell.FLAG_C, sentinelCommand)
        .waitingFor(
            Wait.forSuccessfulCommand("redis-cli -p 26379 SENTINEL master mymaster")
                .withStartupTimeout(StandaloneContainerFactory.DEFAULT_STARTUP_TIMEOUT))
        .withStartupTimeout(StandaloneContainerFactory.DEFAULT_STARTUP_TIMEOUT);
  }

  /**
   * Applies host config: extra hosts for DinD compatibility, optionally NET_ADMIN.
   *
   * @param cmd container create command
   * @param enableNetworkChaos if {@code true}, adds NET_ADMIN capability
   */
  private static void applyHostConfig(
      final com.github.dockerjava.api.command.CreateContainerCmd cmd,
      final boolean enableNetworkChaos) {
    var hostConfig =
        cmd.getHostConfig().withExtraHosts("host.testcontainers.internal:host-gateway");
    if (enableNetworkChaos) {
      hostConfig = hostConfig.withCapAdd(Capability.NET_ADMIN);
    }
    cmd.withHostConfig(hostConfig);
  }
}
