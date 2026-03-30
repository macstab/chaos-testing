/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.Shell;
import com.macstab.chaos.redis.command.RedisCommandBuilder;
import com.macstab.chaos.redis.exception.ClusterCreationException;

/**
 * Builder for Redis Sentinel startup and announce commands.
 *
 * <p><strong>Responsibilities:</strong>
 *
 * <ul>
 *   <li>Building Sentinel startup command with inline configuration
 *   <li>Configuring master, replica, and sentinel announce addresses (for external client access)
 * </ul>
 *
 * <p><strong>Design:</strong> Static utility class — all methods are static, no instances allowed.
 *
 * <p><strong>Announce Configuration:</strong> Required when Sentinel and containers are inside
 * Docker networks. External clients use {@code host.testcontainers.internal} to reach containers
 * via mapped ports, while internal containers communicate via Docker network IPs.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 * @see SentinelContainerFactory
 */
public final class SentinelCommandBuilder {

  private SentinelCommandBuilder() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Builds Sentinel startup command without announce settings.
   *
   * <p>Creates inline Sentinel configuration and starts Sentinel process. Announce settings are
   * added separately via {@link #configureSentinelAnnouncement(GenericContainer)} after startup.
   *
   * @param masterIp master container IP (internal Docker network address)
   * @param quorum sentinel quorum (number of sentinels needed to agree on failover)
   * @return shell command string suitable for container {@code withCommand(Shell.SH, Shell.FLAG_C,
   *     ...)}
   */
  public static String buildSentinelCommandWithoutAnnounce(
      final String masterIp, final int quorum) {
    return RedisCommandBuilder.buildSentinelStartCommand(masterIp, quorum);
  }

  /**
   * Configures master to announce its externally-accessible address.
   *
   * <p>This allows Sentinel to return the correct (mapped) address to clients outside the Docker
   * network.
   *
   * @param master master container (must be running)
   * @throws RuntimeException if configuration fails
   */
  public static void configureMasterAnnouncement(final GenericContainer<?> master) {
    final int masterMappedPort = master.getMappedPort(StandaloneContainerFactory.REDIS_PORT);
    try {
      Shell.exec(master, RedisCommandBuilder.buildAnnounceIpCommand(
          StandaloneContainerFactory.REDIS_PORT, "host.testcontainers.internal"));
      Shell.exec(master, RedisCommandBuilder.buildAnnouncePortCommand(
          StandaloneContainerFactory.REDIS_PORT, masterMappedPort));
    } catch (final Exception e) {
      throw new ClusterCreationException("Failed to configure master announce address", e);
    }
  }

  /**
   * Configures replica to announce its externally-accessible address.
   *
   * <p>This allows the replica to be promoted to master during failover and properly announce
   * itself to Sentinel and clients.
   *
   * @param replica replica container (must be running)
   * @throws RuntimeException if configuration fails
   */
  public static void configureReplicaAnnouncement(final GenericContainer<?> replica) {
    final int replicaMappedPort = replica.getMappedPort(StandaloneContainerFactory.REDIS_PORT);
    try {
      Shell.exec(replica, RedisCommandBuilder.buildAnnounceIpCommand(
          StandaloneContainerFactory.REDIS_PORT, "host.testcontainers.internal"));
      Shell.exec(replica, RedisCommandBuilder.buildAnnouncePortCommand(
          StandaloneContainerFactory.REDIS_PORT, replicaMappedPort));
    } catch (final Exception e) {
      throw new ClusterCreationException("Failed to configure replica announce address", e);
    }
  }

  /**
   * Configures Sentinel announce settings after startup.
   *
   * <p>Configures both:
   *
   * <ul>
   *   <li>Sentinel's own announce address (for other Sentinels/clients to reach it)
   *   <li>Master's announce address (for Sentinel to report to clients)
   * </ul>
   *
   * @param sentinel sentinel container (must be running)
   * @throws RuntimeException if configuration fails
   */
  public static void configureSentinelAnnouncement(final GenericContainer<?> sentinel) {
    final int sentinelMappedPort = sentinel.getMappedPort(StandaloneContainerFactory.SENTINEL_PORT);
    try {
      Shell.exec(sentinel, RedisCommandBuilder.buildSentinelAnnounceIpCommand(
          StandaloneContainerFactory.SENTINEL_PORT, "host.testcontainers.internal"));
      Shell.exec(sentinel, RedisCommandBuilder.buildSentinelAnnouncePortCommand(
          StandaloneContainerFactory.SENTINEL_PORT, sentinelMappedPort));
    } catch (final Exception e) {
      throw new ClusterCreationException("Failed to configure sentinel announce address", e);
    }
  }
}
