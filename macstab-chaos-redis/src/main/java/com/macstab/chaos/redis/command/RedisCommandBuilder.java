/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.command;

import java.util.Objects;

/**
 * Builds redis-cli shell command strings for execution via {@link
 * com.macstab.chaos.core.util.Shell#exec}.
 *
 * <p>All methods return strings suitable for {@code Shell.exec(container, command)}. Commands
 * target specific ports and are OS-agnostic (redis-cli is always available in Redis images).
 *
 * <p><strong>INTERNAL USE ONLY</strong>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisCommandBuilder {

  private RedisCommandBuilder() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Build ROLE command to detect master/replica/sentinel role. */
  public static String buildRoleCommand(final int port) {
    return String.format("redis-cli -p %d ROLE", port);
  }

  /** Build PING command to check if Redis is alive. */
  public static String buildPingCommand(final int port) {
    return String.format("redis-cli -p %d PING", port);
  }

  /** Build CONFIG SET command. */
  public static String buildConfigSet(final int port, final String key, final String value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    return String.format("redis-cli -p %d CONFIG SET %s %s", port, key, value);
  }

  /**
   * Build SENTINEL master command with custom Sentinel port.
   *
   * @param masterName Sentinel master name (e.g., "mymaster") — must not be null
   * @param port Sentinel port (typically 26379)
   * @return redis-cli command string
   */
  public static String buildSentinelMasterCommand(final String masterName, final int port) {
    Objects.requireNonNull(masterName, "masterName");
    return String.format("redis-cli -p %d SENTINEL master %s", port, masterName);
  }

  /**
   * Build SENTINEL master command (default port 26379).
   *
   * @param masterName Sentinel master name — must not be null
   * @return redis-cli command string
   * @deprecated Use {@link #buildSentinelMasterCommand(String, int)} to explicitly specify port
   */
  @Deprecated(since = "2.0", forRemoval = true)
  public static String buildSentinelMasterCommand(final String masterName) {
    return buildSentinelMasterCommand(masterName, 26379);
  }

  /** Build Sentinel startup command (inline config + launch). */
  public static String buildSentinelStartCommand(final String masterIp, final int quorum) {
    Objects.requireNonNull(masterIp, "masterIp");
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

  /** Build replica-announce-ip CONFIG SET (for Testcontainers host routing). */
  public static String buildAnnounceIpCommand(final int port, final String announceHost) {
    Objects.requireNonNull(announceHost, "announceHost");
    return buildConfigSet(port, "replica-announce-ip", announceHost);
  }

  /** Build replica-announce-port CONFIG SET. */
  public static String buildAnnouncePortCommand(final int port, final int announcePort) {
    return buildConfigSet(port, "replica-announce-port", String.valueOf(announcePort));
  }

  /**
   * Build sentinel-announce-ip CONFIG SET with custom Sentinel port.
   *
   * @param port Sentinel port (typically 26379)
   * @param announceHost host to announce — must not be null
   * @return redis-cli command string
   */
  public static String buildSentinelAnnounceIpCommand(final int port, final String announceHost) {
    Objects.requireNonNull(announceHost, "announceHost");
    return String.format("redis-cli -p %d CONFIG SET sentinel-announce-ip %s", port, announceHost);
  }

  /**
   * Build sentinel-announce-ip CONFIG SET (default port 26379).
   *
   * @param announceHost host to announce — must not be null
   * @return redis-cli command string
   * @deprecated Use {@link #buildSentinelAnnounceIpCommand(int, String)} to explicitly specify port
   */
  @Deprecated(since = "2.0", forRemoval = true)
  public static String buildSentinelAnnounceIpCommand(final String announceHost) {
    return buildSentinelAnnounceIpCommand(26379, announceHost);
  }

  /**
   * Build sentinel-announce-port CONFIG SET with custom Sentinel port.
   *
   * @param port Sentinel port (typically 26379)
   * @param announcePort port to announce
   * @return redis-cli command string
   */
  public static String buildSentinelAnnouncePortCommand(final int port, final int announcePort) {
    return String.format("redis-cli -p %d CONFIG SET sentinel-announce-port %d", port, announcePort);
  }

  /**
   * Build sentinel-announce-port CONFIG SET (default port 26379).
   *
   * @param announcePort port to announce
   * @return redis-cli command string
   * @deprecated Use {@link #buildSentinelAnnouncePortCommand(int, int)} to explicitly specify port
   */
  @Deprecated(since = "2.0", forRemoval = true)
  public static String buildSentinelAnnouncePortCommand(final int announcePort) {
    return buildSentinelAnnouncePortCommand(26379, announcePort);
  }
}
