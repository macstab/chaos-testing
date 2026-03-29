/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import java.io.IOException;

import org.testcontainers.containers.GenericContainer;

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
 * @since 2.0
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
   * @return shell command string suitable for container {@code withCommand("sh", "-c", ...)}
   */
  public static String buildSentinelCommandWithoutAnnounce(
      final String masterIp, final int quorum) {
    return "printf \"port 26379\\n"
        + "sentinel monitor mymaster "
        + masterIp
        + " 6379 "
        + quorum
        + "\\n"
        + "sentinel down-after-milliseconds mymaster 2000\\n"
        + "sentinel parallel-syncs mymaster 1\\n"
        + "sentinel failover-timeout mymaster 5000\\n"
        + "\" > /tmp/sentinel.conf && "
        + "redis-server /tmp/sentinel.conf --sentinel";
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
    final Integer masterMappedPort = master.getMappedPort(6379);
    try {
      master.execInContainer(
          "redis-cli", "CONFIG", "SET", "replica-announce-ip", "host.testcontainers.internal");
      master.execInContainer(
          "redis-cli", "CONFIG", "SET", "replica-announce-port", String.valueOf(masterMappedPort));
    } catch (final IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to configure master announce address", e);
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
    final Integer replicaMappedPort = replica.getMappedPort(6379);
    try {
      replica.execInContainer(
          "redis-cli", "CONFIG", "SET", "replica-announce-ip", "host.testcontainers.internal");
      replica.execInContainer(
          "redis-cli",
          "CONFIG",
          "SET",
          "replica-announce-port",
          String.valueOf(replicaMappedPort));
    } catch (final IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to configure replica announce address", e);
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
    final Integer sentinelMappedPort = sentinel.getMappedPort(26379);
    try {
      sentinel.execInContainer(
          "redis-cli",
          "-p", "26379",
          "CONFIG", "SET",
          "sentinel-announce-ip",
          "host.testcontainers.internal");
      sentinel.execInContainer(
          "redis-cli",
          "-p", "26379",
          "CONFIG", "SET",
          "sentinel-announce-port",
          String.valueOf(sentinelMappedPort));
    } catch (final IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to configure sentinel announce address", e);
    }
  }
}
